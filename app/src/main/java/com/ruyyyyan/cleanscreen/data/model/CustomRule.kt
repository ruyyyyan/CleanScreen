package com.ruyyyyan.cleanscreen.data.model

data class CustomRule(
    val id: Long = 0,
    val packageName: String,
    val customKeyword: String = "",
    val customViewId: String = "",
    val customXPercent: Float = 0f, // 0..1 (0 means use auto node)
    val customYPercent: Float = 0f  // 0..1
)
