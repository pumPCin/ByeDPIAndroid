package io.github.dovecoteescapee.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.utility.GoogleVideoUtils
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class TestActivity : AppCompatActivity() {
    private lateinit var sites: List<String>
    private lateinit var cmds: List<String>

    private lateinit var scrollTextView: ScrollView
    private lateinit var progressTextView: TextView
    private lateinit var resultsTextView: TextView
    private lateinit var startStopButton: Button

    private var isTesting = false
    private var originalCmdArgs: String = ""
    private var testJob: Job? = null
    private val proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var proxyIp: String = "127.0.0.1"
    private var proxyPort: Int = 1081

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_test)

        scrollTextView = findViewById(R.id.scrollView)
        startStopButton = findViewById(R.id.startStopButton)
        resultsTextView = findViewById(R.id.resultsTextView)
        progressTextView = findViewById(R.id.progressTextView)

        originalCmdArgs = getPreferences().getString("byedpi_cmd_args", "").toString()
        resultsTextView.movementMethod = LinkMovementMethod.getInstance()

        sites = loadSitesFromFile().toMutableList()
        cmds = loadCmdsFromFile()

        lifecycleScope.launch {
            val previousLogs = loadLogFromFile()

            if (previousLogs.isNotEmpty()) {
                progressTextView.text = getString(R.string.test_complete)
                resultsTextView.text = ""
                displayLog(previousLogs)
            }
        }

        lifecycleScope.launch {
            val googleVideoDomain = GoogleVideoUtils().generateGoogleVideoDomain()

            if (googleVideoDomain != null) {
                (sites as MutableList<String>).add(googleVideoDomain)
                Log.i("TestActivity", "Added auto-generated Google domain: $googleVideoDomain")
            } else {
                Log.e("TestActivity", "Failed to generate Google domain")
            }
        }

        startStopButton.setOnClickListener {
            if (isTesting) {
                stopTesting()
            } else {
                startTesting()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTesting) {
                    stopTesting()
                }
                finish()
            }
        })

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun startTesting() {
        isTesting = true
        startStopButton.text = getString(R.string.test_stop)
        resultsTextView.text = ""
        progressTextView.text = ""

        clearLogFile()

        testJob = lifecycleScope.launch {
            val successfulCmds = mutableListOf<Pair<String, Int>>()
            var cmdIndex = 0

            for (cmd in cmds) {
                cmdIndex++
                progressTextView.text = getString(R.string.test_process, cmdIndex, cmds.size)

                appendTextToResults("$cmd\n")
                appendTextToResults("... ")

                try {
                    startProxyWithCmd("--ip $proxyIp --port $proxyPort $cmd")
                } catch (e: Exception) {
                    appendTextToResults(getString(R.string.test_proxy_error))
                    stopTesting()
                    break
                }

                val checkResults = sites.map { site ->
                    async {
                        val isAccessible = checkSiteAccessibility(site)
                        isAccessible
                    }
                }

                val results = checkResults.awaitAll()

                val successfulCount = results.count { it }
                val successPercentage = (successfulCount * 100) / sites.size

                if (successPercentage >= 50) {
                    successfulCmds.add(cmd to successPercentage)
                }

                appendTextToResults("$successfulCount/${sites.size} ($successPercentage%)\n\n")
                stopProxy()
            }

            successfulCmds.sortByDescending { it.second }

            progressTextView.text = getString(R.string.test_complete)
            appendTextToResults("${getString(R.string.test_good_cmds)}\n\n")

            for ((cmd, success) in successfulCmds) {
                appendLinkToResults("$cmd\n")
                appendTextToResults("$success%\n\n")
            }

            appendTextToResults(getString(R.string.test_complete_info))
            stopTesting()
        }
    }

    private fun stopTesting() {
        isTesting = false
        startStopButton.text = getString(R.string.test_start)

        if (testJob?.isActive == true) {
            testJob?.cancel()
        }

        if (originalCmdArgs.isNotEmpty()) {
            updateCmdInPreferences(originalCmdArgs)
        }

        lifecycleScope.launch {
            if (isProxyRunning()) {
                stopProxy()
            }
        }
    }

    private fun appendTextToResults(text: String) {
        resultsTextView.append(text)

        if (isTesting) {
            saveLogToFile(text)
        }

        scrollToBottom()
    }

    private fun appendLinkToResults(text: String) {
        val spannableString = SpannableString(text)
        val options = arrayOf(
            getString(R.string.cmd_history_apply),
            getString(R.string.cmd_history_copy)
        )

        spannableString.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    AlertDialog.Builder(this@TestActivity)
                        .setTitle(getString(R.string.cmd_history_menu))
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> updateCmdInPreferences(text.trim())
                                1 -> copyToClipboard(text.trim())
                            }
                        }
                        .show()
                }
            },
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        resultsTextView.append(spannableString)

        if (isTesting) {
            saveLogToFile("{$text}")
        }

        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollTextView.post {
            scrollTextView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun updateCmdInPreferences(cmd: String) {
        val sharedPreferences = getPreferences()
        val editor = sharedPreferences.edit()
        editor.putString("byedpi_cmd_args", cmd)
        editor.apply()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("command", text)
        clipboard.setPrimaryClip(clip)
    }

    private suspend fun checkSiteAccessibility(site: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val connectProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyIp, proxyPort))

                val formattedUrl = if (!site.startsWith("http://") && !site.startsWith("https://")) {
                    "https://$site"
                } else {
                    site
                }

                val url = URL(formattedUrl)
                val connection = url.openConnection(connectProxy) as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000

                val responseCode = connection.responseCode
                Log.i("CheckSite", "Good response $site ($responseCode)")
                true
            } catch (e: Exception) {
                Log.e("CheckSite", "Error response $site")
                false
            }
        }
    }

    private suspend fun startProxyWithCmd(cmd: String) {
        Log.i("TestDPI", "Starting test proxy with cmd: $cmd")
        updateCmdInPreferences(cmd)
        val preferences = getByeDpiPreferences()

        if (isProxyRunning()) {
            Log.i("TestDPI", "Previous proxy is running, stopping")
            stopProxy()
        }

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                proxy.startProxy(preferences)
            } catch (e: Exception) {
                Log.e("TestDPI", "Error starting proxy", e)
            }
        }

        waitForProxyToStart()
    }

    private suspend fun stopProxy() {
        Log.i("TestDPI", "Stopping test proxy")
        proxyJob = null

        try {
            proxy.stopProxy()
        } catch (e: Exception) {
            Log.e("TestDPI", "Error stopping proxy", e)
        }

        waitForProxyToStop()
    }

    private suspend fun waitForProxyToStart(timeout: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            if (isProxyRunning()) {
                Log.i("TestDPI", "Proxy started")
                return true
            }
            delay(100)
        }
        return false
    }

    private suspend fun waitForProxyToStop(timeout: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!isProxyRunning()) {
                Log.i("TestDPI", "Proxy stopped")
                return true
            }
            delay(100)
        }
        return false
    }

    private suspend fun isProxyRunning(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                socket.connect(InetSocketAddress(proxyIp, proxyPort), 500)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun loadSitesFromFile(): List<String> {
        val inputStream = assets.open("proxytest_sites.txt")
        return inputStream.bufferedReader().useLines { it.toList() }
    }

    private fun loadCmdsFromFile(): List<String> {
        val inputStream = assets.open("proxytest_cmds.txt")
        return inputStream.bufferedReader().useLines { it.toList() }
    }

    private fun saveLogToFile(log: String) {
        val file = File(filesDir, "proxy_test.log")
        file.appendText(log)
    }

    private fun loadLogFromFile(): String {
        val file = File(filesDir, "proxy_test.log")
        return if (file.exists()) {
            file.readText()
        } else {
            ""
        }
    }

    private fun clearLogFile() {
        val file = File(filesDir, "proxy_test.log")
        file.writeText("")
    }

    private fun displayLog(log: String) {
        log.split("{", "}").forEachIndexed { index, part ->
            if (index % 2 == 0) {
                appendTextToResults(part)
            } else {
                appendLinkToResults(part)
            }
        }
    }
}

