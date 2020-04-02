package com.wly.handlerdemo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private val checkHandler = CheckHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        check()
        btn_check.setOnClickListener {
            Thread.sleep(2000)
        }
    }

    private fun check(){
        Looper.getMainLooper().setMessageLogging {
            if (it.startsWith(">>>>> Dispatching to")){
                checkHandler.onStart()
            }else if (it.startsWith("<<<<< Finished to")){
                checkHandler.onEnd()
            }
        }
    }
    class CheckHandler {
        private val mHandlerThread = HandlerThread("卡顿检测")
        private var mHandler : Handler

        private val runnable = Runnable {
            log()
        }

        fun onStart(){
            mHandler.postDelayed(runnable , 1000)
        }

        fun onEnd(){
            mHandler.removeCallbacksAndMessages(null)
        }

        private fun log() {
            val sb = StringBuilder()
            val stackTrace = Looper.getMainLooper().thread.stackTrace
            stackTrace.forEach {
                sb.append("$it\n")
            }
            Log.w("TAG", sb.toString())
        }

        init {
            mHandlerThread.start()
            mHandler = Handler(mHandlerThread.looper)
        }
    }
}
