package com.gameops.craft.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** One material input / produced output line of a recipe version. */
public class ItemQty {
    @NotBlank
    private String itemCode;
    @Positive
    private long qty;

    public ItemQty() {}

    public ItemQty(String itemCode, long qty) {
        this.itemCode = itemCode;
        this.qty = qty;
    }

    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public long getQty() { return qty; }
    public void setQty(long qty) { this.qty = qty; }
}
