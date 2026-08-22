package com.example.myapplication

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TfliteClassifier(context: Context) {

    private val interpreter: Interpreter

    init {
        val opts = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(loadModel(context), opts)
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd("model.tflite").use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel = fis.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    /** output: [normal, tachy, brady] */
    fun predict(input30: FloatArray): FloatArray {
        require(input30.size == 30) { "Expected 30 floats, got ${input30.size}" }
        val input = arrayOf(input30)            // [1,30]
        val output = Array(1) { FloatArray(3) } // [1,3]
        interpreter.run(input, output)
        return output[0]
    }

    fun close() = interpreter.close()
}
