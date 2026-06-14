package com.secure.chat.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class TargetLLMModule(private val context: Context) {

    companion object {
        private const val TAG = "TargetLLM"
        private const val OLLAMA_URL = "http://localhost:11434/api/generate"
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    data class LLMConfig(
        val mode: String = "ollama",
        val model: String = "llama3:8b",
        val apiKey: String = "",
        val systemPrompt: String = "You are a helpful assistant named SELZX. Respond concisely.",
        val maxTokens: Int = 256
    )

    private var config = LLMConfig()

    fun updateConfig(newConfig: LLMConfig) {
        config = newConfig
    }

    fun trigger(prompt: String, callback: (String) -> Unit) {
        if (isRunning) {
            callback("LLM busy")
            return
        }

        isRunning = true
        scope.launch {
            try {
                val result = when (config.mode) {
                    "ollama" -> queryOllama(prompt)
                    "openai" -> queryOpenAI(prompt)
                    else -> "Unknown mode: ${config.mode}"
                }
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback("LLM Error: ${e.message}") }
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun queryOllama(prompt: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("model", config.model)
                    put("prompt", "$config.systemPrompt\n\nUser: $prompt\n\nAssistant:")
                    put("stream", false)
                }

                val conn = URL(OLLAMA_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 60000

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(json.toString())
                writer.flush()
                writer.close()

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                JSONObject(response).optString("response", "No response")
            } catch (e: Exception) {
                "Ollama error: ${e.message}"
            }
        }
    }

    private suspend fun queryOpenAI(prompt: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val messagesArray = org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", config.systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                }

                val json = JSONObject().apply {
                    put("model", config.model)
                    put("messages", messagesArray)
                    put("max_tokens", config.maxTokens)
                }

                val conn = URL(OPENAI_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 60000

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(json.toString())
                writer.flush()
                writer.close()

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val respJson = JSONObject(response)
                val choices = respJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: "No response"
                } else {
                    "Error: ${respJson.optJSONObject("error")?.optString("message", "Unknown")}"
                }
            } catch (e: Exception) {
                "OpenAI error: ${e.message}"
            }
        }
    }

    fun destroy() { scope.cancel(); isRunning = false }
}