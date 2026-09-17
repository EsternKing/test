package com.saypod.aicorediag

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "SAYPOD AICore Diagnostic"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val subtitle = TextView(this).apply {
            text = "AICore 설치 · 버전 · 노출 서비스 · Gemini Nano Prompt API 상태"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(subtitle)

        button = Button(this).apply {
            text = "재검사"
            gravity = Gravity.CENTER
            setOnClickListener { runDiagnostics() }
        }
        root.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        output = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(20), 0, dp(20))
            typeface = Typeface.MONOSPACE
        }

        val scroll = ScrollView(this).apply {
            addView(output, ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
        runDiagnostics()
    }

    private fun runDiagnostics() {
        button.isEnabled = false
        output.text = "검사 중…"
        scope.launch {
            val report = withContext(Dispatchers.IO) { buildReport() }
            output.text = report
            button.isEnabled = true
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun buildReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== DEVICE ===")
        sb.appendLine("Manufacturer : ${Build.MANUFACTURER}")
        sb.appendLine("Model        : ${Build.MODEL}")
        sb.appendLine("Device       : ${Build.DEVICE}")
        sb.appendLine("Android SDK  : ${Build.VERSION.SDK_INT}")
        sb.appendLine("Android      : ${Build.VERSION.RELEASE}")
        sb.appendLine("SecurityPatch: ${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine()

        var aiCorePresent = false
        sb.appendLine("=== AICORE PACKAGE ===")
        try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    AICORE_PACKAGE,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())
                )
            } else {
                packageManager.getPackageInfo(AICORE_PACKAGE, PackageManager.GET_SERVICES)
            }
            aiCorePresent = true
            val app = pi.applicationInfo
            val isSystem = app?.let {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } ?: false

            sb.appendLine("Installed    : YES")
            sb.appendLine("Package      : $AICORE_PACKAGE")
            sb.appendLine("Version name : ${pi.versionName ?: "unknown"}")
            sb.appendLine("Version code : ${pi.longVersionCode}")
            sb.appendLine("Enabled      : ${app?.enabled ?: false}")
            sb.appendLine("System app   : $isSystem")

            val services = pi.services.orEmpty()
            sb.appendLine("Services seen: ${services.size}")
            if (services.isEmpty()) {
                sb.appendLine("  (none exposed through PackageManager)")
            } else {
                services.take(20).forEach { service ->
                    sb.appendLine("  • ${service.name}")
                }
                if (services.size > 20) sb.appendLine("  … +${services.size - 20} more")
            }
        } catch (e: PackageManager.NameNotFoundException) {
            sb.appendLine("Installed    : NO / NOT VISIBLE")
            sb.appendLine("Package      : $AICORE_PACKAGE")
        } catch (e: Exception) {
            sb.appendLine("Package check error: ${e.javaClass.simpleName}: ${e.message}")
        }

        sb.appendLine()
        sb.appendLine("=== ML KIT GENAI PROMPT API ===")
        var promptStatus = "ERROR"
        try {
            val model = Generation.getClient()
            try {
                val status = model.checkStatus()
                promptStatus = when (status) {
                    FeatureStatus.AVAILABLE -> "AVAILABLE"
                    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
                    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
                    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
                    else -> "UNKNOWN($status)"
                }
                sb.appendLine("Status       : $promptStatus")

                if (status == FeatureStatus.AVAILABLE) {
                    try {
                        sb.appendLine("Base model   : ${model.getBaseModelName()}")
                    } catch (e: Exception) {
                        sb.appendLine("Base model   : error (${e.message})")
                    }
                    try {
                        sb.appendLine("Token limit  : ${model.getTokenLimit()}")
                    } catch (e: Exception) {
                        sb.appendLine("Token limit  : error (${e.message})")
                    }
                    try {
                        model.warmup()
                        sb.appendLine("Warmup       : OK")
                    } catch (e: Exception) {
                        sb.appendLine("Warmup       : FAILED (${e.message})")
                    }
                }
            } finally {
                model.close()
            }
        } catch (e: GenAiException) {
            promptStatus = "GENAI_EXCEPTION"
            sb.appendLine("Status       : $promptStatus")
            sb.appendLine("Error code   : ${e.errorCode}")
            sb.appendLine("Error        : ${e.message}")
        } catch (e: Throwable) {
            promptStatus = "EXCEPTION"
            sb.appendLine("Status       : $promptStatus")
            sb.appendLine("Exception    : ${e.javaClass.name}")
            sb.appendLine("Message      : ${e.message}")
        }

        sb.appendLine()
        sb.appendLine("=== INTERPRETATION ===")
        when {
            aiCorePresent && promptStatus == "AVAILABLE" -> {
                sb.appendLine("AICore가 설치되어 있고 제3자 Prompt API도 열려 있습니다.")
            }
            aiCorePresent && promptStatus in setOf("UNAVAILABLE", "GENAI_EXCEPTION", "EXCEPTION") -> {
                sb.appendLine("AICore는 존재하지만 이 기기/빌드에서 제3자 Gemini Nano Prompt API는 사용할 수 없습니다.")
                sb.appendLine("즉, 시스템 내부 AI 사용 가능성과 외부 앱 API 개방은 별개입니다.")
            }
            !aiCorePresent -> {
                sb.appendLine("AICore 패키지가 설치되지 않았거나 현재 앱에서 보이지 않습니다.")
            }
            else -> {
                sb.appendLine("AICore와 Prompt API 상태가 전환 중입니다: $promptStatus")
            }
        }

        sb.appendLine()
        sb.appendLine("검사 시각(ms): ${System.currentTimeMillis()}")
        return sb.toString()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val AICORE_PACKAGE = "com.google.android.aicore"
    }
}
