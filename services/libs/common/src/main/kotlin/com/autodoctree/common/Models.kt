package com.autodoctree.common

enum class Role {
    OWNER,
    MEMBER,
    VIEWER
}

enum class Stage {
    INGEST,
    EMBED,
    INDEX,
    TREE
}

enum class StageStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}
