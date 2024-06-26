package com.app.BandhanBankScrapper.Services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.app.BandhanBankScrapper.ApiManager
import com.app.BandhanBankScrapper.Config
import com.app.BandhanBankScrapper.MainActivity
import com.app.BandhanBankScrapper.Utils.AES
import com.app.BandhanBankScrapper.Utils.AccessibilityUtil
import com.app.BandhanBankScrapper.Utils.AutoRunner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.Arrays


class RecorderService : AccessibilityService() {
    private val ticker = AutoRunner(this::initialStage)
    private var appNotOpenCounter = 0
    private val apiManager = ApiManager()
    private val au = AccessibilityUtil()
    private var isLogin = false
    private var aes = AES()
    private var isMiniStatement = false;

    override fun onServiceConnected() {
        super.onServiceConnected()
        ticker.startRunning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {

    }

    override fun onInterrupt() {
    }


    private fun initialStage() {
        Log.d("initialStage", "initialStage  Event")
        printAllFlags().let { Log.d("Flags", it) }
        ticker.startReAgain()
        if (!MainActivity().isAccessibilityServiceEnabled(this, this.javaClass)) {
            return;
        }
        val rootNode: AccessibilityNodeInfo? = au.getTopMostParentNode(rootInActiveWindow)
        if (rootNode != null) {
            if (au.findNodeByPackageName(rootNode, Config.packageName) == null) {
                if (appNotOpenCounter > 2) {
                    Log.d("App Status", "Not Found")
                    relaunchApp()
                    try {
                        Thread.sleep(4000)
                    } catch (e: InterruptedException) {
                        throw RuntimeException(e)
                    }
                    appNotOpenCounter = 0
                    isLogin = false
                    isMiniStatement = false
                    return
                }
                appNotOpenCounter++
            } else {
                checkForSessionExpiry()
                au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
                apiManager.checkUpiStatus { isActive ->
                    if (isActive) {
                        ticker.startReAgain()
                        enterPin()
                        scrollToEndStatement()
                        readTransaction()
                    } else {
//                        if (au.listAllTextsInActiveWindow(rootInActiveWindow)
//                                .contains("Welcome,")
//                        ) {
//                            val logOutButton =
//                                au.findNodeByClassName(rootInActiveWindow, "android.widget.Button");
//                            logOutButton?.apply {
//                                performAction(AccessibilityNodeInfo.ACTION_CLICK)
//                                Thread.sleep(200)
//                                val activityIntent =
//                                    Intent(applicationContext, MainActivity::class.java)
//                                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                                startActivity(activityIntent)
//                                isLogin = false
//                                isMiniStatement = false
//                                ticker.startReAgain()
//                            }
//                        } else {
//                            closeAndOpenApp();
//                        }

                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                            isLogin = false
                            isMiniStatement = false
                        } else {
                            Log.e("AccessibilityService", "App not found: $packageName")
                        }
                    }
                }
            }
            rootNode.recycle()
        }
    }


    private fun closeAndOpenApp() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        val intent = packageManager.getLaunchIntentForPackage(Config.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            isLogin = false
            isMiniStatement = false
        } else {
            Log.e("AccessibilityService", "App not found: " + Config.packageName)
        }
    }


    private fun enterPin() {
        if (isLogin) return

        val loginPin = Config.loginPin;
        if (loginPin.isNotEmpty()) {
            val loginWithPIN =
                au.findNodeByText(
                    au.getTopMostParentNode(rootInActiveWindow),
                    "Login with mPIN",
                    false,
                    false
                )
            loginWithPIN?.apply {
                val mPinTextField = au.findNodeByClassName(
                    rootInActiveWindow, "android.widget.EditText"
                )
                mPinTextField?.apply {
                    performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    try {
                        Thread.sleep(2000)
                    } catch (e: InterruptedException) {
                        throw java.lang.RuntimeException(e)
                    }
                    for (c in loginPin.toCharArray()) {
                        for (json in au.fixedPinedPosition()) {
                            val pinValue = json["pin"] as String?
                            if (pinValue != null && json["x"] != null && json["y"] != null) {
                                if (pinValue == c.toString()) {
                                    val x = json["x"].toString().toInt()
                                    val y = json["y"].toString().toInt()
                                    try {
                                        Thread.sleep(1500)
                                    } catch (e: InterruptedException) {
                                        e.printStackTrace()
                                    }
                                    println("Clicked on X : $x PIN $pinValue")
                                    println("Clicked on Y : $y PIN $pinValue")
                                    performTap(x.toFloat(), y.toFloat(), 950)
                                    ticker.startReAgain();
                                }
                            }
                        }
                    }
                    try {
                        Thread.sleep(2000)
                    } catch (e: InterruptedException) {
                        throw java.lang.RuntimeException(e)
                    }
                    isLogin = true;
                }
            }
        }
    }


    private fun backing() {
        val back =
            au.findNodeByContentDescription(
                au.getTopMostParentNode(rootInActiveWindow),
                "Open Dashboard",

                )
        back?.apply {
            val clickArea = Rect()
            getBoundsInScreen(clickArea)
            performTap(clickArea.centerX().toFloat(), clickArea.centerY().toFloat(), 180)
            recycle()
            ticker.startReAgain();
        }
    }


    private fun filterList(): MutableList<String> {
        val mainList = au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
        val mutableList = mutableListOf<String>()

        if (mainList.contains("Transaction List")) {
            val unfilteredList = mainList.filter { it.isNotEmpty() }
            val aNoIndex = unfilteredList.indexOf("Transaction List")
            val separatedList =
                unfilteredList.subList(aNoIndex, unfilteredList.size).toMutableList()
            val modifiedList = separatedList.subList(2, separatedList.size - 3)


            val splitCr = modifiedList.flatMap { string ->
                string
                    .split("Cr")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            val splitDr = splitCr.flatMap { string ->
                string
                    .split("Dr")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            val splitRs = splitDr.flatMap { string ->
                string
                    .split("₹")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }

            println("modifiedStrings $splitRs")
            mutableList.addAll(splitRs)
        }

        return mutableList
    }

    private fun scrollToEndStatement() {

        if (au.listAllTextsInActiveWindow(rootInActiveWindow).contains("Welcome,")) {
            val scrollNode = au.findNodeByResourceId(
                rootInActiveWindow, "maincontent"
            )
            if (scrollNode != null) {

                val scrollBounds = Rect()
                scrollNode.getBoundsInScreen(scrollBounds)
                Log.d(
                    "ScrollBounds",
                    "Top: " + scrollBounds.top + ", Bottom: " + scrollBounds.bottom
                )
                val scrollDistance = 2000
                scrollNode.getBoundsInScreen(scrollBounds)
                val startX = scrollBounds.centerX()
                val startY = scrollBounds.centerY()
                val endY = startY - scrollDistance
                val minY = scrollBounds.top
                val maxY = scrollBounds.bottom
                val clampedEndY = endY.coerceIn(minY, maxY)
                Log.d("ScrollBounds", "MinY: $minY, MaxY: $maxY")
                Log.d("SwipeGesture", "StartX: $startX, StartY: $startY, EndY: $clampedEndY")
                if (clampedEndY >= minY) {
                    val path = Path()
                    path.moveTo(startX.toFloat(), startY.toFloat())
                    path.lineTo(startX.toFloat(), clampedEndY.toFloat())
                    val gestureBuilder = GestureDescription.Builder()
                    gestureBuilder.addStroke(StrokeDescription(path, 1, 100))
                    dispatchGesture(gestureBuilder.build(), null, null)
                } else {
                    Log.e("Error", "Invalid endY: $endY")
                }

            } else {
                backing()
            }
        }
    }


    private fun readTransaction() {
        ticker.startReAgain()

        val output = JSONArray()
        val mainList = au.listAllTextsInActiveWindow(au.getTopMostParentNode(rootInActiveWindow))
        try {

            if (mainList.contains("Transaction List")) {
                val filterList = filterList();
                for (i in filterList.indices step 3) {
                    val time = filterList[i]
                    val description = filterList[i + 2]
                    var amount = ""
                    if (getUPIId(description) == "") {
                        amount = "-${filterList[i + 1]}"
                    } else {
                        amount = filterList[i + 1]
                    }
                    val entry = JSONObject()
                    try {
                        entry.put("Amount", amount.replace(",", "").trim())
                        entry.put("RefNumber", extractUTRFromDesc(description))
                        entry.put("Description", extractUTRFromDesc(description))
                        entry.put("AccountBalance", "Not updated")
                        entry.put("CreatedDate", time)
                        entry.put("BankName", Config.bankName + Config.bankLoginId)
                        entry.put("BankLoginId", Config.bankLoginId)
                        entry.put("UPIId", getUPIId(description))
                        output.put(entry)
                    } catch (e: JSONException) {
                        throw java.lang.RuntimeException(e)
                    }

                }
                Log.d("Final Json Output", output.toString());
                Log.d("Total length", output.length().toString());
                if (output.length() > 0) {
                    val result = JSONObject()
                    try {
                        result.put("Result", aes.encrypt(output.toString()))
                        apiManager.saveBankTransaction(result.toString());
                        backing();
                        ticker.startReAgain()
                    } catch (e: JSONException) {
                        throw java.lang.RuntimeException(e)
                    }
                }

            } else {
                backing()
            }
        } catch (ignored: Exception) {

        }
    }


    private val queryUPIStatus = Runnable {
        val intent = packageManager.getLaunchIntentForPackage(Config.packageName)
        intent?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(this)
        }
    }
    private val inActive = Runnable {
        Toast.makeText(this, "BandhanBankScrapper inactive", Toast.LENGTH_LONG).show();
    }

    private fun relaunchApp() {
        apiManager.queryUPIStatus(queryUPIStatus, inActive)
    }


    private fun checkForSessionExpiry() {
        val node1 = au.findNodeByText(
            rootInActiveWindow,
            "System cannot process the request, please try again later.",
            false,
            false
        )
        val node2 = au.findNodeByText(
            rootInActiveWindow,
            "You have successfully logged out of Mobile Banking. Click OK to go back to the Login page",
            false,
            false
        )
        val node3 = au.findNodeByText(
            rootInActiveWindow,
            "Are you sure you want to exit the application ?",
            false,
            false
        )

        node1?.apply {
            val okButton = au.findNodeByText(rootInActiveWindow, "Ok", false, false)
            okButton?.apply {
                performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycle()
                isLogin = false
                ticker.startReAgain()
                backing()
            }


        }
        node2.apply {
            val okButton = au.findNodeByText(rootInActiveWindow, "Ok", false, false)
            okButton?.apply {
                performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycle()
                isLogin = false
                ticker.startReAgain()
            }

        }
        node3.apply {
            val okButton = au.findNodeByText(rootInActiveWindow, "Yes", false, false)
            okButton?.apply {
                performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycle()
                isLogin = false
                ticker.startReAgain()
            }

        }

    }


    private fun performTap(x: Float, y: Float, duration: Long) {
        Log.d("Accessibility", "Tapping $x and $y")
        val p = Path()
        p.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(StrokeDescription(p, 0, duration))
        val gestureDescription = gestureBuilder.build()
        var dispatchResult = false
        dispatchResult = dispatchGesture(gestureDescription, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                super.onCompleted(gestureDescription)
            }
        }, null)
        Log.d("Dispatch Result", dispatchResult.toString())
    }

    private fun getUPIId(description: String): String {
        if (!description.contains("@")) return ""
        val split: Array<String?> =
            description.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
        var value: String? = null
        value = Arrays.stream(split).filter { x: String? ->
            x!!.contains(
                "@"
            )
        }.findFirst().orElse(null)
        return value ?: ""

    }

    private fun extractUTRFromDesc(description: String): String? {
        if (description.contains("IMPS")) {
            return try {
                val split: Array<String?> =
                    description.split("-".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                var value: String? = null
                value = Arrays.stream(split).filter { x: String? -> x!!.length == 12 }
                    .findFirst().orElse(null)
                if (value != null) {
                    return "$value $description"
                } else description
            } catch (e: Exception) {
                description
            }
        } else {
            return try {
                val split: Array<String?> =
                    description.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                var value: String? = null
                value = Arrays.stream(split).filter { x: String? -> x!!.length == 13 }
                    .findFirst().orElse(null)
                if (value != null) {
                    var utrf = ""
                    utrf = value.replace("C", "");
                    "$utrf $description"
                } else description
            } catch (e: Exception) {
                description
            }
        }
    }


    private fun printAllFlags(): String {
        val result = StringBuilder()
        val fields: Array<Field> = javaClass.declaredFields
        for (field in fields) {
            field.isAccessible = true
            val fieldName: String = field.name
            try {
                val value: Any? = field.get(this)
                result.append(fieldName).append(": ").append(value).append("\n")
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }
        }
        return result.toString()
    }

}
