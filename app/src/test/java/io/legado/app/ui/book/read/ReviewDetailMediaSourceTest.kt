package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewDetailMediaSourceTest {

    @Test
    fun `review images and preview keep the captured source`() {
        val source = dialogSource()

        assertTrue(
            source.contains(
                "RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceKey)"
            )
        )
        listOf("item.avatar", "badge").forEach { image ->
            val model = Regex.escape(image)
            assertTrue(
                Regex(
                    """ImageLoader\.load\(context, $model\)\s*""" +
                        """\.apply\(sourceImageOptions\)"""
                ).containsMatchIn(source)
            )
        }
        assertTrue(source.contains("bindReviewImage(context, imageView, imageUrl)"))
        assertTrue(
            Regex(
                """ImageLoader\.load\(context, imageUrl\)\s*""" +
                    """\.apply\(sourceImageOptions\)"""
            ).containsMatchIn(source)
        )
        assertTrue(source.contains("requireActivity().openPhoto(imageUrl, sourceKey)"))
    }

    @Test
    fun `review audio reuses source aware media item`() {
        val source = dialogSource()
        val toggleBlock = source.substringAfter("private fun toggleAudioPlayback(")
            .substringBefore("private fun releaseAudioPlayer(")

        assertTrue(toggleBlock.contains("val source = reviewSource ?: return"))
        assertTrue(toggleBlock.contains("source = source"))
        assertTrue(toggleBlock.contains(").getMediaItem()"))
        assertTrue(source.contains("val source: BaseSource"))
        assertTrue(source.contains("result?.source?.let { reviewSource = it }"))
    }

    @Test
    fun `reply badges use the shared badge binding path`() {
        val rowBlock = dialogSource()
            .substringAfter("val replyIndent = mainAvatarSize")
            .substringBefore("private companion object")
        val replyBranch = rowBlock.indexOf("if (item.isReply) {")
        val badgeVisibility = rowBlock.indexOf("if (item.badges.isNotEmpty())")
        val badgeBinding = rowBlock.indexOf("bindBadges(context, llBadges, item.badges)")
        val contentVisibility = rowBlock.indexOf("val hasText")

        assertTrue(badgeVisibility in 0 until replyBranch)
        assertTrue(badgeBinding in 0 until replyBranch)
        assertTrue(contentVisibility > replyBranch)
    }

    @Test
    fun `reply target keeps the commenter header and prefixes the body`() {
        val rowBlock = dialogSource()
            .substringAfter("private fun ReviewCommentRow(")
            .substringBefore("val hasText")

        assertTrue(rowBlock.contains("text = item.name.orEmpty()"))
        assertTrue(rowBlock.contains("val replyToName = item.replyToName.orEmpty().trim()"))
        assertTrue(rowBlock.contains("val prefix = \"\u56de\u590d \$replyToName\uff1a\""))
    }

    @Test
    fun `reply likes are shown only for positive counts`() {
        val rowBlock = dialogSource()
            .substringAfter("private fun ReviewCommentRow(")
            .substringBefore("private companion object")

        assertTrue(
            rowBlock.contains(
                "val showLikeArea = !item.isReply || (likeCount != null && likeCount > 0)"
            )
        )
        assertTrue(rowBlock.contains("if (showLikeArea)"))
    }

    @Test
    fun `embedded replies are expanded without loading paged replies`() {
        val source = dialogSource()
        val flatten = source.substringAfter("private fun flattenItems(")
            .substringBefore("private fun renderUiItems()")

        assertTrue(flatten.contains("val isExpanded = loadedReplyCount > 0 ||"))
        assertTrue(flatten.contains("expandedReplyParentKeys.contains(parentKey)"))
        assertTrue(flatten.contains("val canLoadMore = hasReplyUrl"))

        val rowBlock = source.substringAfter("private fun ReviewCommentRow(")
            .substringBefore("private companion object")
        assertTrue(rowBlock.contains("loadReplies(parentKey)"))
        assertFalse(rowBlock.contains("detail.replies.isNotEmpty()"))
    }

    @Test
    fun `review image restores its cached aspect ratio before reloading`() {
        val binding = dialogSource()
            .substringAfter("private fun bindReviewImage(")
            .substringBefore("private fun bindBadges(")
        val clearRequest = binding.indexOf("Glide.with(context).clear(imageView)")
        val resetHeight = binding.indexOf("height = ViewGroup.LayoutParams.WRAP_CONTENT")
        val emptyImage = binding.indexOf("if (imageUrl.isNullOrBlank())")
        val cachedSize = binding.indexOf("imageSizes[imageUrl]?.let")
        val intoImage = binding.indexOf("imageRequest.into(imageView)")

        assertTrue(clearRequest in 0 until emptyImage)
        assertTrue(resetHeight in 0 until emptyImage)
        assertTrue(cachedSize in emptyImage until intoImage)
        assertFalse(binding.contains("imageView.width"))
        assertTrue(binding.contains("val width = resource.intrinsicWidth"))
        assertTrue(binding.contains("val height = resource.intrinsicHeight"))
        assertTrue(binding.contains("if (width > 0 && height > 0)"))
        assertTrue(binding.contains("imageSizes[imageUrl] = Size(width, height)"))
        assertTrue(binding.contains("intrinsicWidth = size.width"))
        assertTrue(binding.contains("intrinsicHeight = size.height"))
        assertTrue(binding.contains("imageRequest.placeholder(placeholder).error(placeholder)"))
    }

    private fun dialogSource(): String = projectFile(
        "src/main/java/io/legado/app/ui/book/read/ReviewDetailDialog.kt"
    ).readText().replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
