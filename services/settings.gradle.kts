rootProject.name = "autodoc-services"

include(
    "doc-api",
    "worker-ingest",
    "worker-embed",
    "worker-index",
    "worker-tree",
    "libs:common",
    "libs:contracts"
)
