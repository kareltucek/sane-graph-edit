rootProject.name = "sane-graph-edit"

// model <- editor-core, client-core <- editor-host <- client-swing (the application)
listOf("model", "editor-core", "editor-host", "client-core", "client-swing").forEach {
    include(":$it")
    project(":$it").projectDir = file("modules/$it")
}
