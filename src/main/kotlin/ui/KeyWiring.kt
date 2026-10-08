package ui

import client.Actions
import client.KeyMapper

/**
 * The key mapper of this application: the client's [KeyMapper]
 * connected to the commands in [CommandRegistry] (which must be
 * registered first) and to Swing's timer. Every view it is given
 * as its target is a [GraphView].
 */
fun applicationKeyMapper(): KeyMapper {
    val mapper = KeyMapper(KeyMapper.defaultBindings(), SwingScheduler)
    mapper.commandExecutor = { cmd, target -> CommandRegistry.execute(cmd, target as GraphView) }
    mapper.commandNames = { CommandRegistry.list() }
    mapper.markSetter = { target, letter -> Actions.setMark(target.model, letter) }
    mapper.markRecaller = { target, letter -> Actions.recallMark(target.model, letter) }
    mapper.linkFollower = { target, letter -> (target as GraphView).followFileLink(letter, quiet = true) }
    mapper.applicationSettings["picker"] = { value -> FilePicker.command = value.ifBlank { FilePicker.DEFAULT_COMMAND } }
    mapper.installDefaultTransformBindings()
    return mapper
}
