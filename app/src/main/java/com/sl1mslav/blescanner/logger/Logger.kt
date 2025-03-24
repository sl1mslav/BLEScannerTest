package com.sl1mslav.blescanner.logger

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors

object Logger {
    private var logFile: File? = null

    private val logExecutor = Executors.newCachedThreadPool()

    fun init(context: Context) {
        logFile = File(
            context.cacheDir,
            LOG_FILE_NAME
        )
    }

    fun log(text: String, e: Exception? = null) {
        try {
            val stackTrace = Thread.currentThread().getStackTrace()
            logExecutor.execute {
                // Печатаем информацию о классе и методе, вызвавших текущую функцию
                if (stackTrace.size > 4) {
                    val className = stackTrace[4].className.substringAfterLast(".")
                    val methodName = stackTrace[4].methodName
                    if (e != null) {
                        Log.e("BleScanner", ":: $className.$methodName :: $text", e)
                        appendTextToFileWithHeader("ERROR \t:: $className.$methodName :: $text")
                    }
                    else {
                        Log.d("BleScanner", ":: $className.$methodName :: $text")
                        appendTextToFileWithHeader("LOG \t:: $className.$methodName :: $text")
                    }
                } else {
                    if (e != null) {
                        appendTextToFileWithHeader("ERROR \t-- $text")
                        Log.e("BleScanner", "-- $text", e)
                    }
                    else {
                        appendTextToFileWithHeader("LOG \t-- $text")
                        Log.d("BleScanner", "-- $text")
                    }
                }
            }
        } catch (e1: Exception) {
            e1.printStackTrace()
            e?.printStackTrace()
        }
    }

    private fun appendTextToFileWithHeader(text: String) {
        if (logFile == null) {
            Log.d("Logger", "appendTextToFileWithHeader: logFile is null.")
            return
        }

        val fileWriter = FileWriter(
            logFile,
            true
        )

        val bufferedWriter = BufferedWriter(fileWriter)

        val fileReader = FileReader(logFile)
        val bufferedReader = BufferedReader(fileReader)
        val lines = bufferedReader.readLines()
        bufferedReader.close()

        if (lines.size > 3000) {
            logFile?.writeText(lines.drop(lines.size - 100).joinToString("\n"))
        }

        bufferedWriter.write("\n" + SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(Date().time) + "\t" + text)

        bufferedWriter.close()
        fileWriter.close()
    }

    const val LOG_FILE_NAME = "logs.txt"
}