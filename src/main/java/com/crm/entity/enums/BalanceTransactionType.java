package com.crm.entity.enums;

public enum BalanceTransactionType {
    /** PER_LESSON: davomat belgilanganda dars narxi yechiladi (manfiy). */
    LESSON_CHARGE,
    /** PER_LESSON: davomat billable bo'lmay qolganda qaytariladi (musbat). */
    LESSON_REFUND,
    /** To'lov: kassaga tushgan real pul (musbat). */
    PAYMENT,
    /** MONTHLY: sotib olingan davr qiymati yechiladi (manfiy). */
    PERIOD_CHARGE,
    /** MONTHLY: to'lov bekor qilinsa/o'chirilsa davr qiymati qaytariladi (musbat). */
    PERIOD_REFUND,
    FREEZE,
    UNFREEZE,
    MANUAL_ADJUST
}
