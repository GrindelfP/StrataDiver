package shot

import to.grindelf.stratadiver.StrataDiver
import java.io.File

/**
 * Runs the game for a number of frames and writes what it looked like to a PNG.
 *
 * This is the answer to a question the project has been unable to ask on demand. Every
 * claim this renderer makes is about *appearance*, and until now the only way to check one
 * was for a person to launch the game and look at it. That does not scale to a rewrite of
 * the renderer's core, and it is unavailable at two in the morning.
 *
 * Reading the framebuffer back answers the same question honestly: it is the default
 * framebuffer after the GUI has been drawn over the picture, so the file is what a player
 * would have seen. `tools/check.sh` proves the numbers; this proves the picture, and the
 * picture is the thing being argued about.
 *
 *   tools/shot.sh <frames> <out.png>
 */
fun main(args: Array<String>) {
    val frames = args.getOrNull(0)?.toIntOrNull() ?: 60
    val out = args.getOrNull(1) ?: "target/shot.png"

    File(out).parentFile?.mkdirs()
    println("running $frames frames, writing $out")

    val game = StrataDiver()
    game.capture(frames, out)
}