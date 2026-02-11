package com.autodoctree.api.domain

enum class TreeViewType(val apiValue: String) {
    TOPIC("topic"),
    PROJECT("project"),
    TIMELINE("timeline"),
    VERSION("version"),
    TEMPLATE("template");

    companion object {
        fun fromApi(value: String?): TreeViewType {
            if (value.isNullOrBlank()) {
                return TOPIC
            }
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { entry -> entry.apiValue == normalized } ?: TOPIC
        }

        fun fromDb(value: String?): TreeViewType {
            if (value.isNullOrBlank()) {
                return TOPIC
            }
            val normalized = value.trim().uppercase()
            return entries.firstOrNull { entry -> entry.name == normalized } ?: TOPIC
        }
    }
}
