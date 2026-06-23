package fulguris.adblock

import android.net.Uri
import android.util.Base64
import fulguris.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.IDN
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

@Singleton
class GiteeHostsUpdater @Inject constructor(
    private val abpListUpdater: AbpListUpdater,
    private val abpBlockerManager: AbpBlockerManager
) {

    enum class Status {
        ADDED_AND_FILTER_UPDATED,
        ADDED_FILTER_NOT_FOUND,
        ADDED_FILTER_UPDATE_FAILED,
        ALREADY_EXISTS,
        INVALID_LINK,
        MISSING_TOKEN,
        FAILED
    }

    data class Result(
        val status: Status,
        val domain: String = "",
        val detail: String = ""
    )

    private data class RemoteFile(
        val content: String,
        val sha: String
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 从链接提取域名，并添加到 Gitee hosts 文件。
     */
    fun addDomainFromLink(linkUrl: String): Result {
        val domain = extractDomain(linkUrl)
            ?: return Result(Status.INVALID_LINK)

        val token = BuildConfig.GITEE_ACCESS_TOKEN.trim()

        if (token.isEmpty()) {
            return Result(
                status = Status.MISSING_TOKEN,
                domain = domain
            )
        }

        return try {
            // 如果两次提交之间文件 SHA 发生变化，重新读取并重试一次。
            repeat(2) {
                val remoteFile = readRemoteFile(token)

                if (containsDomain(remoteFile.content, domain)) {
                    // 即使 Gitee 中已经存在，也尝试更新本机过滤器。
                    refreshSubscribedFilter(remoteFile.content)
                
                    return Result(
                        status = Status.ALREADY_EXISTS,
                        domain = domain
                    )
                }

                val newContent = appendRule(
                    content = remoteFile.content,
                    domain = domain
                )

                val response = updateRemoteFile(
                    token = token,
                    sha = remoteFile.sha,
                    content = newContent,
                    domain = domain
                )

                if (response.first in 200..299) {
                    return when (refreshSubscribedFilter(newContent)) {
                        FilterRefreshStatus.UPDATED ->
                            Result(
                                status = Status.ADDED_AND_FILTER_UPDATED,
                                domain = domain
                            )
                
                        FilterRefreshStatus.NOT_FOUND ->
                            Result(
                                status = Status.ADDED_FILTER_NOT_FOUND,
                                domain = domain
                            )
                
                        FilterRefreshStatus.FAILED ->
                            Result(
                                status = Status.ADDED_FILTER_UPDATE_FAILED,
                                domain = domain
                            )
                    }
                }

                // 409 通常表示文件已被其他提交修改，下一轮重新获取 SHA。
                if (response.first != 409) {
                    return Result(
                        status = Status.FAILED,
                        domain = domain,
                        detail = apiErrorMessage(
                            response.second,
                            "HTTP ${response.first}"
                        )
                    )
                }
            }

            Result(
                status = Status.FAILED,
                domain = domain,
                detail = "文件发生并发修改，请重试"
            )
        } catch (exception: Exception) {
            Result(
                status = Status.FAILED,
                domain = domain,
                detail = exception.message
                    ?: exception.javaClass.simpleName
            )
        }
    }

    /**
     * 只接受 HTTP 和 HTTPS 链接。
     */
    private fun extractDomain(linkUrl: String): String? {
        val uri = Uri.parse(linkUrl.trim())

        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (scheme != "http" && scheme != "https") {
            return null
        }

        val host = uri.host
            ?.trim()
            ?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return try {
            IDN.toASCII(host)
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 获取 hosts 文件内容和当前 SHA。
     */
    private fun readRemoteFile(token: String): RemoteFile {
        val url = "$API_URL" +
                "?access_token=${Uri.encode(token)}" +
                "&ref=${Uri.encode(BRANCH)}"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    apiErrorMessage(
                        responseText,
                        "读取 Gitee 文件失败：HTTP ${response.code}"
                    )
                )
            }

            val json = JSONObject(responseText)

            val sha = json.optString("sha")
            val encodedContent = json.optString("content")

            if (sha.isBlank() || encodedContent.isBlank()) {
                throw IOException("Gitee 返回的文件内容或 SHA 为空")
            }

            val decodedContent = Base64.decode(
                encodedContent,
                Base64.DEFAULT
            ).toString(Charsets.UTF_8)

            return RemoteFile(
                content = decodedContent,
                sha = sha
            )
        }
    }

    /**
     * 更新 Gitee hosts 文件。
     */
    private fun updateRemoteFile(
        token: String,
        sha: String,
        content: String,
        domain: String
    ): Pair<Int, String> {
        val encodedContent = Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val formBody = FormBody.Builder()
            .add("content", encodedContent)
            .add("sha", sha)
            .add("branch", BRANCH)
            .add("message", "Add $domain to hosts from Fulguris")
            .build()

        val url = "$API_URL?access_token=${Uri.encode(token)}"

        val request = Request.Builder()
            .url(url)
            .put(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    /**
     * 防止同一个域名重复添加。
     */
    private fun containsDomain(
        content: String,
        domain: String
    ): Boolean {
        return content.lineSequence()
            .map { line ->
                line.substringBefore('#').trim()
            }
            .filter { it.isNotEmpty() }
            .any { line ->
                val parts = line.split(Regex("\\s+"))

                parts.size >= 2 &&
                        parts.last()
                            .trimEnd('.')
                            .equals(domain, ignoreCase = true)
            }
    }

    private fun appendRule(
        content: String,
        domain: String
    ): String {
        val rule = "0.0.0.0 $domain"

        val normalizedContent = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd()

        return if (normalizedContent.isEmpty()) {
            "$rule\n"
        } else {
            "$normalizedContent\n$rule\n"
        }
    }

    private fun apiErrorMessage(
        responseText: String,
        fallback: String
    ): String {
        if (responseText.isBlank()) {
            return fallback
        }

        return try {
            JSONObject(responseText)
                .optString("message")
                .takeIf { it.isNotBlank() }
                ?: fallback
        } catch (_: Exception) {
            responseText.take(200)
        }
    }

    private enum class FilterRefreshStatus {
        UPDATED,
        NOT_FOUND,
        FAILED
    }
    
    /**
     * 使用刚刚上传到 Gitee 的最新 hosts 内容，
     * 直接更新 Fulguris 中已订阅的远程过滤器。
     */
    private fun refreshSubscribedFilter(
        content: String
    ): FilterRefreshStatus {
    
        val entity = abpListUpdater.abpDao
            .getAll()
            .firstOrNull { item ->
                normalizeFilterUrl(item.url) ==
                        normalizeFilterUrl(RAW_FILTER_URL)
            }
            ?: return FilterRefreshStatus.NOT_FOUND
    
        val originalUrl = entity.url
    
        val tempFile = try {
            File.createTempFile(
                "gitee-hosts-",
                ".txt",
                abpListUpdater.context.cacheDir
            )
        } catch (_: Exception) {
            return FilterRefreshStatus.FAILED
        }
    
        val updated = try {
            // 将 Gitee 中的新内容写入临时 Hosts 文件。
            tempFile.writeText(
                content,
                Charsets.UTF_8
            )
    
            // 确保临时文件修改时间晚于旧过滤器更新时间。
            tempFile.setLastModified(
                System.currentTimeMillis() + 1_000
            )
    
            // 防止此前该过滤器处于关闭状态。
            entity.enabled = true
    
            // 临时让更新器读取本地文件，避免 Gitee raw 缓存。
            entity.url = Uri.fromFile(tempFile).toString()
    
            abpListUpdater.updateAbpEntity(
                entity,
                true
            )
        } catch (_: Exception) {
            false
        } finally {
            // 恢复正常的 Gitee raw 订阅地址，
            // 以后自动更新仍然从 Gitee 下载。
            entity.url = originalUrl
            entity.enabled = true
    
            abpListUpdater.abpDao.update(entity)
    
            tempFile.delete()
        }
    
        if (!updated) {
            return FilterRefreshStatus.FAILED
        }
    
        return try {
            abpBlockerManager.reloadListsNow()
            FilterRefreshStatus.UPDATED
        } catch (_: Exception) {
            FilterRefreshStatus.FAILED
        }
    }
    
    private fun normalizeFilterUrl(
        url: String
    ): String {
        return url
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
    }

    companion object {
        private const val OWNER = "libriejin_1"
        private const val REPOSITORY = "filter-gate"
        private const val BRANCH = "master"
        private const val FILE_PATH = "hosts"
        
        private const val RAW_FILTER_URL =
            "https://gitee.com/libriejin_1/filter-gate/raw/master/hosts"

        private const val API_URL =
            "https://gitee.com/api/v5/repos/" +
                    "$OWNER/$REPOSITORY/contents/$FILE_PATH"
    }
}
