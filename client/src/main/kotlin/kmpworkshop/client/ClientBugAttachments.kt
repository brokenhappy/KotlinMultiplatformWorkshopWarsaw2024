package kmpworkshop.client

import kmpworkshop.common.BugImageAttachment
import kmpworkshop.common.MaxBugAttachmentBytes
import kmpworkshop.common.MaxBugAttachmentCount
import kmpworkshop.common.MaxBugAttachmentTotalBytes
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

internal const val MaxBugImagePixels = 40_000_000

internal fun imageAttachmentFromFile(file: File, existingCount: Int): Result<BugImageAttachment> = runCatching {
    require(existingCount < MaxBugAttachmentCount) { "You can attach at most $MaxBugAttachmentCount images." }
    require(file.isFile && file.length() <= MaxBugAttachmentBytes) { "That image is too large." }
    val image = ImageIO.read(file) ?: error("That file is not a supported image.")
    image.toBugImageAttachment(file.name)
}

internal fun imageAttachmentFromImage(image: Image, existingCount: Int): Result<BugImageAttachment> = runCatching {
    require(existingCount < MaxBugAttachmentCount) { "You can attach at most $MaxBugAttachmentCount images." }
    image.toBugImageAttachment("pasted-image.png")
}

private fun Image.toBugImageAttachment(fileName: String): BugImageAttachment {
    val width = getWidth(null)
    val height = getHeight(null)
    require(width > 0 && height > 0) { "The image has no dimensions." }
    require(width.toLong() * height <= MaxBugImagePixels) { "That image has too many pixels." }

    val buffered = if (this is BufferedImage && type == BufferedImage.TYPE_INT_ARGB) {
        this
    } else {
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
            val graphics = target.createGraphics()
            try {
                graphics.drawImage(this, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }
    val bytes = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(buffered, "png", output)) { "Could not encode the image as PNG." }
        output.toByteArray()
    }
    require(bytes.size <= MaxBugAttachmentBytes) { "The encoded image is too large." }
    return BugImageAttachment(
        fileName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "image.png" },
        mimeType = "image/png",
        dataBase64 = Base64.getEncoder().encodeToString(bytes),
    )
}

internal fun attachmentBytes(attachments: List<BugImageAttachment>): Int = attachments.sumOf {
    ((it.dataBase64.length * 3) / 4).coerceAtMost(MaxBugAttachmentBytes)
}.coerceAtMost(MaxBugAttachmentTotalBytes)
