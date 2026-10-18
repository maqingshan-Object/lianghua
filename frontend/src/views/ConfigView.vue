<template>
  <div class="page">
    <div class="card" v-if="cfg">
      <h3>打分模式 <span class="muted">当前版本 <span class="ver-tag">v{{ cfg.version }}</span> · {{ cfg.updated_at }}</span></h3>
      <div class="seg">
        <button :class="{active: cfg.mode === 'ENGINE_ONLY'}" @click="cfg.mode = 'ENGINE_ONLY'">纯引擎</button>
        <button :class="{active: cfg.mode === 'AI_ONLY'}" @click="cfg.mode = 'AI_ONLY'">纯AI</button>
        <button :class="{active: cfg.mode === 'FUSION'}" @click="cfg.mode = 'FUSION'">融合</button>
      </div>
      <div class="muted" style="margin-top:6px">纯引擎最稳定 · 纯AI最激进 · 融合取两者加权</div>
    </div>

    <div class="card" v-if="cfg && cfg.mode === 'FUSION'">
      <h3>融合参数 <span class="muted">final = AI×w1 + 引擎×w2 ≥ 阈值 入选</span></h3>
      <div class="row" style="gap:6px;margin-bottom:10px">
        <button v-for="(p, k) in cfg.presets" :key="k" class="btn ghost small" @click="applyPreset(k)">{{ p.label }}：阈值{{ p.threshold }}</button>
      </div>
      <div class="field"><label>AI权重 {{ cfg.ai_weight.toFixed(2) }}</label>
        <input type="range" min="0" max="1" step="0.05" v-model.number="cfg.ai_weight" @input="cfg.engine_weight = +(1 - cfg.ai_weight).toFixed(2)" /></div>
      <div class="field"><label>引擎权重 {{ cfg.engine_weight.toFixed(2) }}</label>
        <input type="range" min="0" max="1" step="0.05" v-model.number="cfg.engine_weight" @input="cfg.ai_weight = +(1 - cfg.engine_weight).toFixed(2)" /></div>
      <div class="field"><label>入选阈值 {{ cfg.threshold }}（越高越保守）</label>
        <input type="range" min="40" max="90" step="1" v-model.number="cfg.threshold" /></div>
    </div>

    <div class="card" v-if="cfg">
      <h3>过滤条件</h3>
      <div class="grid2">
        <div class="field"><label>上市满（天）</label><input type="number" v-model.number="cfg.filters.min_list_days" /></div>
        <div class="field"><label>日成交额下限（万元）</label><input type="number" v-model.number="cfg.filters.min_amount" /></div>
        <div class="field"><label>PE上限</label><input type="number" v-model.number="cfg.filters.pe_limit" /></div>
        <div class="field"><label>资产负债率上限（%）</label><input type="number" v-model.number="cfg.filters.debt_ratio_limit" /></div>
      </div>
    </div>

    <div class="card" v-if="cfg">
      <h3>因子权重</h3>
      <div v-for="(w, k) in cfg.factor_weights" :key="k" class="field">
        <label>{{ factorName(k) }} {{ (+w).toFixed(2) }}</label>
        <input type="range" min="0" max="1" step="0.05" v-model.number="cfg.factor_weights[k]" />
      </div>
    </div>

    <div class="card" v-if="cfg">
      <h3>交易参数（模拟盘）</h3>
      <div class="grid2">
        <div class="field"><label>最多持仓（只）</label><input type="number" v-model.number="cfg.trade.max_positions" /></div>
        <div class="field"><label>单只上限（%）</label><input type="number" v-model.number="cfg.trade.max_single_pct" /></div>
        <div class="field"><label>总仓位上限（%）</label><input type="number" v-model.number="cfg.trade.max_total_pct" /></div>
        <div class="field"><label>止损（%）</label><input type="number" v-model.number="cfg.trade.stop_loss_pct" /></div>
        <div class="field"><label>止盈（%）</label><input type="number" v-model.number="cfg.trade.take_profit_pct" /></div>
        <div class="field"><label>初始资金（元）</label><input type="number" v-model.number="cfg.trade.initial_capital" /></div>
      </div>
    </div>

    <div class="card" v-if="cfg">
      <h3>策略启停</h3>
      <div v-for="st in cfg.strategies" :key="st.id" class="between" style="padding:8px 0;border-bottom:1px solid var(--line)">
        <div>{{ st.name }} <span class="muted">{{ st.id }}</span></div>
        <div class="row" style="gap:8px">
          <input type="range" min="0" max="1.5" step="0.1" v-model.number="st.weight" style="width:90px" title="权重" />
          <button class="btn small" :class="st.enabled ? '' : 'ghost'" @click="st.enabled = !st.enabled">{{ st.enabled ? '启用中' : '已停用' }}</button>
        </div>
      </div>
    </div>

    <div class="card" v-if="cfg">
      <button class="btn" @click="save" :disabled="saving" style="width:100%">{{ saving ? '保存中…' : '保存配置' }}</button>
      <div class="muted" style="text-align:center;margin-top:6px">保存后生成新版本，下一交易日生效</div>
    </div>

    <div class="card" v-if="cfg">
      <h3>版本历史</h3>
      <div v-for="v in cfg.versions" :key="v.version" class="between" style="padding:8px 0;border-bottom:1px solid var(--line)">
        <div><b class="ver-tag">v{{ v.version }}</b> <span class="muted">{{ v.time }}</span><br /><span style="font-size:13px">{{ v.summary }}</span></div>
        <button v-if="v.version !== cfg.version" class="btn ghost small" @click="rollback(v.version)">回滚</button>
        <span v-else class="badge ok">当前</span>
      </div>
    </div>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api/index.js'

const cfg = ref(null)
const saving = ref(false)
const toast = ref('')
const factorName = (k) => ({ trend: '趋势', breakout: '突破', volume: '放量', fundamental: '基本面' }[k] || k)

function showToast(t) { toast.value = t; setTimeout(() => (toast.value = ''), 2200) }
function applyPreset(k) {
  const p = cfg.value.presets[k]
  cfg.value.ai_weight = p.ai_weight; cfg.value.engine_weight = p.engine_weight; cfg.value.threshold = p.threshold
  showToast(`已应用「${p.label}」预设，记得保存`)
}
async function save() {
  saving.value = true
  try {
    const r = await api.saveConfig(cfg.value)
    showToast(r.message || '已保存，下一交易日生效')
    cfg.value = await api.getConfig()
  } finally { saving.value = false }
}
async function rollback(v) {
  const r = await api.rollbackConfig(v)
  showToast(r.message || `已回滚到 v${v}`)
  cfg.value = await api.getConfig()
}
onMounted(async () => { cfg.value = await api.getConfig() })
</script>
