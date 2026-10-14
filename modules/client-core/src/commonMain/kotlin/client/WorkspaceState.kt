package client

/** Where the state of the workspace [workspaceDir] is kept, below [configDir]. */
fun Workspace.stateDir(workspaceDir: Path = dir, configDir: Path = Fs.configDir): Path =
    configDir.resolve("workspaces").resolve(encode(workspaceDir))

/** The session of the workspace [workspaceDir]; the current one's by default. */
fun Workspace.session(workspaceDir: Path = dir, configDir: Path = Fs.configDir): Session =
    Session(stateDir(workspaceDir, configDir).resolve("session.properties"))
