/*
 * Copyright 2018 Allan Wang
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pitchedapps.frost.facebook.requests

import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.pitchedapps.frost.facebook.FB_IMAGE_ID_MATCHER
import com.pitchedapps.frost.facebook.FB_REDIRECT_URL_MATCHER
import com.pitchedapps.frost.facebook.FB_URL_BASE
import com.pitchedapps.frost.facebook.formattedFbUrl
import com.pitchedapps.frost.facebook.get
import com.pitchedapps.frost.utils.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import java.io.IOException
import java.io.InputStream

/**
 * Created by Allan Wang on 29/12/17.
 */
fun RequestAuth.getFullSizedImage(fbid: Long) = frostRequest(::getJsonUrl) {
    url("${FB_URL_BASE}photo/view_full_size/?fbid=$fbid&__ajax__=&__user=$userId")
    get()
}

/**
 * Attempts to get the fbcdn url of the supplied image redirect url
 */
suspend fun String.getFullSizedImageUrl(url: String, timeout: Long = 3000): String? =
    withContext(Dispatchers.IO) {
        try {
            withTimeout(timeout) {
                val redirect = requestBuilder().url(url).get().call()
                    .execute().body()?.string() ?: return@withTimeout null
                FB_REDIRECT_URL_MATCHER.find(redirect)[1]?.formattedFbUrl
            }
        } catch (e: Exception) {
            L.e(e) { "Failed to load full size image url" }
            null
        }
    }

/**
 * Request loader for a potentially hd version of a url
 * In this case, each url may potentially return an id,
 * which may potentially be used to fetch a higher res image url
 * The following aims to allow such loading while adhering to Glide's lifecycle
 */
data class HdImageMaybe(val url: String, val cookie: String) {

    val id: Long by lazy { FB_IMAGE_ID_MATCHER.find(url)[1]?.toLongOrNull() ?: -1 }

    val isValid: Boolean by lazy {
        id != -1L && cookie.isNotBlank()
    }
}

/*
 * The following was a test to see if hd image loading would work
 *
 * It's working and tested, though the improvements aren't really worth the extra data use
 * and reload
 */

class HdImageLoadingFactory : ModelLoaderFactory<HdImageMaybe, InputStream> {

    override fun build(multiFactory: MultiModelLoaderFactory) = HdImageLoading()

    override fun teardown() = Unit
}

fun <T> RequestBuilder<T>.loadWithPotentialHd(model: HdImageMaybe) =
    thumbnail(clone().load(model.url))
        .load(model)
        .apply(RequestOptions().override(Target.SIZE_ORIGINAL))

class HdImageLoading : ModelLoader<HdImageMaybe, InputStream> {

    override fun buildLoadData(
        model: HdImageMaybe,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? =
        if (!model.isValid) null
        else ModelLoader.LoadData(ObjectKey(model), HdImageFetcher(model))

    override fun handles(model: HdImageMaybe) = model.isValid
}

class HdImageFetcher(private val model: HdImageMaybe) : DataFetcher<InputStream> {

    @Volatile
    private var cancelled: Boolean = false
    private var urlCall: Call? = null
    private var inputStream: InputStream? = null

    private fun DataFetcher.DataCallback<in InputStream>.fail(msg: String) {
        onLoadFailed(RuntimeException(msg))
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (!model.isValid) return callback.fail("Model is invalid")
        val result: Result<InputStream?> = runCatching {
            runBlocking {
                withTimeout(20000L) {
                    val auth = fbAuth.fetch(model.cookie).await()
                    if (cancelled) throw RuntimeException("Cancelled")
                    val url = auth.getFullSizedImage(model.id).invoke()
                        ?: throw RuntimeException("Null url")
                    if (cancelled) throw RuntimeException("Cancelled")
                    if (!url.contains("png") && !url.contains("jpg")) throw RuntimeException("Invalid format")
                    urlCall?.execute()?.body()?.byteStream()
                }
            }
        }
        if (result.isSuccess)
            callback.onDataReady(result.getOrNull())
        else
            callback.onLoadFailed(
                result.exceptionOrNull() as? Exception ?: RuntimeException("Failed")
            )
    }

    override fun cleanup() {
        try {
            inputStream?.close()
        } catch (e: IOException) {
        } finally {
            inputStream = null
        }
    }

    override fun cancel() {
        cancelled = true
        urlCall?.cancel()
        urlCall = null
        cleanup()
    }
}
