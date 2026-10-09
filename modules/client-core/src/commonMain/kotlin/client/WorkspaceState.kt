package client

/** Where the state of the workspace [workspaceDir] is kept, below [configDir]. */
fun Workspace.stateDir(workspaceDir: Path = dir, configDir: Path = Fs.configDir): Path =
    configDir.resolve("workspaces").resolve(encode(workspaceDir))

/** The session of the current workspace. */
fun Workspace.session(): Session = Session(stateDir().resolve("session.properties"))
