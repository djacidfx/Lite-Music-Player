package org.akanework.gramophone.ui.components

import android.widget.ImageView
import coil3.Image
import coil3.ImageLoader
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.target.ImageViewTarget

class NoPlaceholderImageViewTarget(view: ImageView) : ImageViewTarget(view) {
    override fun onStart(placeholder: Image?) {
        // do nothing
    }
}

inline fun ImageView.loadNoPlaceholder(
    data: Any?,
    imageLoader: ImageLoader = context.imageLoader,
    builder: ImageRequest.Builder.() -> Unit = {},
): Disposable {
    val request = ImageRequest.Builder(context)
        .data(data)
        .target(NoPlaceholderImageViewTarget(this))
        .apply(builder)
        .build()
    return imageLoader.enqueue(request)
}