package ui

import client.Actions
import client.CommandRegistry
import client.KeyMapper

/**
 * The key mapper of this application: the client's [KeyMapper]
 * connected to the commands in [CommandRegistry] (which must be
 * registered first) and to Swing's timer. The shell of every view it
 * is given is a [GraphView].
 */
fun applicationKeyMapper(): KeyMapper {
    val mapper = KeyMapper(KeyMapper.defaultBindings(), SwingScheduler, defaultMappings = KeyMapper.defaultKeyMappings())
    mapper.commandExecutor = { cmd, view -> CommandRegistry.execute(cmd, view) }
    mapper.commandNames = { CommandRegistry.list() }
    mapper.markSetter = { view, letter, change -> Actions.setMark(view, letter, change) }
    mapper.markRecaller = { view, letter -> Actions.recallMark(view, letter) }
    mapper.linkFollower = { view, letter, quiet -> view.content.followFileLink(letter, quiet) }
    mapper.installDefaultTransformBindings()
    return mapper
}
