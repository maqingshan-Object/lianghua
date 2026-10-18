package com.quant.paper.model;

import java.time.LocalDate;

/** 模拟账户（paper_account 单行，id=1）。 */
public record PaperAccount(
        double cash,
        double totalAsset,
        LocalDate updatedDate,
        int versionId
) {}
