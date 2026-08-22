# Keep the JavaScript bridge entry point. Everything else may be optimized normally.
-keepclassmembers class com.moyu.chat.MainActivity$MoyuHostBridge { @android.webkit.JavascriptInterface <methods>; }
