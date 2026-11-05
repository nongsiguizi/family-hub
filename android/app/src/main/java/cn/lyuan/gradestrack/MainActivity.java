package cn.lyuan.gradestrack;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 家庭事项记录 · 安卓客户端
 *
 * 只是一个原生 WebView 外壳：页面、数据、鉴权全部由私有服务器上的 H5 提供。
 * 服务器地址保存在本机 SharedPreferences，可随时切换内网 / 外网地址。
 */
public class MainActivity extends Activity {

    private static final String PREF = "grades_track";
    private static final String KEY_URLS = "server_urls";
    private static final String KEY_CUR = "server_current";
    private static final String DEFAULT_URL = "http://192.168.5.132:8999/";
    private static final int REQ_FILE = 1001;
    private static final int REQ_STORAGE = 1002;

    private SharedPreferences prefs;
    private WebView web;
    private ProgressBar progress;
    private View errorView;
    private TextView errorText;
    private String currentUrl = DEFAULT_URL;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackTime = 0;
    private boolean pageLoaded = false;

    /* 注入到页面的桥接脚本：
     * 1) 页面里的导出（Blob / data URL）与附件预览（window.open(blob:)）在 WebView 中
     *    默认不会下载，这里统一转成 base64 交给 Java 保存到系统「下载」目录；
     * 2) WebView 没有系统打印能力，给出明确提示，避免出现点了没反应的按钮。
     */
    private static final String BRIDGE_JS =
        "(function(){if(window.__gtBridgeReady)return;window.__gtBridgeReady=true;" +
        "function mimeOf(n){n=String(n||'').toLowerCase();" +
        "if(/\\.pdf$/.test(n))return 'application/pdf';" +
        "if(/\\.csv$/.test(n))return 'text/csv';" +
        "if(/\\.json$/.test(n))return 'application/json';" +
        "if(/\\.(jpg|jpeg)$/.test(n))return 'image/jpeg';" +
        "if(/\\.png$/.test(n))return 'image/png';" +
        "if(/\\.html?$/.test(n))return 'text/html';return '';}" +
        "function saveUrl(u,name){" +
        " if(u.indexOf('data:')===0){var i=u.indexOf(',');" +
        " GradesTrack.saveBase64(u.slice(i+1),name||'download',mimeOf(name));return true;}" +
        " if(u.indexOf('blob:')!==0)return false;" +
        " try{var x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='blob';" +
        " x.onload=function(){var fr=new FileReader();" +
        " fr.onload=function(){var d=String(fr.result),k=d.indexOf(',');" +
        " GradesTrack.saveBase64(d.slice(k+1),name||'download',mimeOf(name)||x.response.type||'');};" +
        " fr.onerror=function(){GradesTrack.toast('文件读取失败');};fr.readAsDataURL(x.response);};" +
        " x.onerror=function(){GradesTrack.toast('文件读取失败');};x.send();return true;}" +
        " catch(e){GradesTrack.toast('文件读取失败');return false;}}" +
        "document.addEventListener('click',function(e){" +
        " var t=e.target;var a=t&&t.closest?t.closest('a[download]'):null;if(!a)return;" +
        " var href=a.getAttribute('href')||'';" +
        " if(saveUrl(href,a.getAttribute('download'))){e.preventDefault();e.stopPropagation();}" +
        "},true);" +
        "var _open=window.open;window.open=function(u){" +
        " if(typeof u==='string'&&(u.indexOf('blob:')===0||u.indexOf('data:')===0)){" +
        "  if(saveUrl(u,'附件预览'))return {};return null;}" +
        " return _open.apply(window,arguments);};" +
        "window.print=function(){GradesTrack.toast('安卓客户端暂不支持直接打印，请在电脑浏览器打开后用「打印 / 存为 PDF」');};" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        currentUrl = normalizeUrl(prefs.getString(KEY_CUR, DEFAULT_URL));
        getWindow().setStatusBarColor(Color.parseColor("#4338ca"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#4f46e5"));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText("家庭事项记录");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        bar.addView(title);
        bar.addView(makeBarButton("服务器", new View.OnClickListener() {
            @Override public void onClick(View v) { showServerDialog(); }
        }));
        bar.addView(makeBarButton("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        }));
        bar.addView(makeBarButton("更多", new View.OnClickListener() {
            @Override public void onClick(View v) { showMoreDialog(); }
        }));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setBackgroundColor(Color.parseColor("#e0e7ff"));
        progress.getProgressDrawable().setColorFilter(Color.parseColor("#f59e0b"), android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        progress.setVisibility(View.GONE);

        FrameLayout stage = new FrameLayout(this);
        web = new WebView(this);
        stage.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout err = new LinearLayout(this);
        err.setOrientation(LinearLayout.VERTICAL);
        err.setGravity(Gravity.CENTER);
        err.setBackgroundColor(Color.WHITE);
        err.setPadding(dp(28), dp(20), dp(28), dp(20));
        err.setVisibility(View.GONE);
        TextView errTitle = new TextView(this);
        errTitle.setText("无法连接私有服务器");
        errTitle.setTextSize(19);
        errTitle.setTextColor(Color.parseColor("#1e293b"));
        errTitle.setGravity(Gravity.CENTER);
        err.addView(errTitle);
        errorText = new TextView(this);
        errorText.setTextSize(13);
        errorText.setTextColor(Color.parseColor("#64748b"));
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, dp(14), 0, dp(18));
        err.addView(errorText);
        LinearLayout errBtns = new LinearLayout(this);
        errBtns.setOrientation(LinearLayout.HORIZONTAL);
        errBtns.setGravity(Gravity.CENTER);
        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundColor(Color.parseColor("#4f46e5"));
        retry.setPadding(dp(20), dp(9), dp(20), dp(9));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        errBtns.addView(retry);
        TextView change = new TextView(this);
        change.setText("修改地址");
        change.setTextColor(Color.parseColor("#4f46e5"));
        change.setPadding(dp(20), dp(9), dp(20), dp(9));
        change.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showServerDialog(); }
        });
        errBtns.addView(change);
        err.addView(errBtns);
        errorView = err;
        stage.addView(err, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        configureWebView();

        if (!prefs.contains(KEY_CUR)) {
            showFirstServerDialog();
        } else {
            reload();
        }
    }

    private TextView makeBarButton(String text, View.OnClickListener listener) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setPadding(dp(11), dp(7), dp(11), dp(7));
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int v) {
        return Math.round(Resources.getSystem().getDisplayMetrics().density * v);
    }

    /* ---------------- WebView ---------------- */

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // 登录 token 存在 localStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);                // 尊重 H5 的 viewport，手机端自适应
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " GradesTrackAndroid/2.0");

        web.addJavascriptInterface(new JsBridge(), "GradesTrack");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    if (!sameHost(u)) {
                        openExternally(u, null);
                        return true;
                    }
                    return false;
                }
                if ("tel".equalsIgnoreCase(scheme) || "mailto".equalsIgnoreCase(scheme)) {
                    openExternally(u, null);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoaded = false;
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                progress.setVisibility(View.GONE);
                errorView.setVisibility(View.GONE);
                web.evaluateJavascript(BRIDGE_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    String msg = Build.VERSION.SDK_INT >= 23 ? String.valueOf(error.getDescription()) : "网络请求失败";
                    showError(msg);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    showError("服务器返回 HTTP " + errorResponse.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler,
                                           android.net.http.SslError error) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("证书不受信任")
                    .setMessage("私有服务器使用了自签名或过期证书。确认地址无误后才继续。")
                    .setPositiveButton("仍然继续", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { handler.proceed(); }
                    })
                    .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { handler.cancel(); showError("已取消不安全的连接"); }
                    })
                    .show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 100) progress.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择试卷 PDF 或图片"), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("无法打开文件选择器");
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url.startsWith("blob:") || url.startsWith("data:")) return; // 交给 JS 桥接
                downloadByManager(url, contentDisposition, mimeType);
            }
        });
    }

    private boolean sameHost(Uri u) {
        Uri cur = Uri.parse(currentUrl);
        String a = u.getHost(), b = cur.getHost();
        if (a == null || b == null) return true;
        // 端口不同视为同一台私有服务器的不同服务时，仍允许外链判断用 host 比较
        return a.equalsIgnoreCase(b);
    }

    private void openExternally(Uri u, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("没有可以打开此文件的应用");
        }
    }

    private void downloadByManager(String url, String disposition, String mime) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setMimeType(mime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                fileNameFrom(url, disposition, mime));
            dm.enqueue(r);
            toast("开始下载，完成后在「下载」目录查看");
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        }
    }

    private String fileNameFrom(String url, String disposition, String mime) {
        String name = null;
        if (disposition != null) {
            int p = disposition.toLowerCase().indexOf("filename*=");
            if (p >= 0) {
                String seg = disposition.substring(p + 10).replace("\"", "").trim();
                int q = seg.indexOf("''");
                if (q >= 0) seg = seg.substring(q + 2);
                try { name = URLDecoder.decode(seg.split(";")[0].trim(), "UTF-8"); } catch (Exception ignored) {}
            }
            if (name == null) {
                p = disposition.toLowerCase().indexOf("filename=");
                if (p >= 0) name = disposition.substring(p + 9).replace("\"", "").split(";")[0].trim();
            }
        }
        if (name == null || name.length() == 0) {
            String path = Uri.parse(url).getLastPathSegment();
            name = (path == null || path.length() == 0) ? "grades-track-download" : path;
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.contains(".") && mime != null && mime.contains("/")) {
            String ext = mime.substring(mime.indexOf('/') + 1);
            if ("jpeg".equals(ext)) ext = "jpg";
            name = name + "." + ext;
        }
        return name;
    }

    /* ---------------- JS 桥接 ---------------- */

    private class JsBridge {
        @android.webkit.JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
            });
        }

        @android.webkit.JavascriptInterface
        public void saveBase64(final String b64, final String name, final String mime) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                final String safe = (name == null ? "download" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                final Uri uri = saveToDownloads(data, safe, mime);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("文件已保存")
                            .setMessage("已保存到「下载 / " + safe + "」")
                            .setPositiveButton("打开", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    if (uri != null) openExternally(uri, mime);
                                }
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                    }
                });
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            }
        }
    }

    private Uri saveToDownloads(byte[] data, String name, String mime) throws Exception {
        String type = (mime == null || mime.length() == 0 || mime.contains("octet-stream"))
            ? guessMime(name) : mime;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, type);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("无法创建下载文件");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("无法写入下载文件");
            os.write(data);
            os.flush();
            os.close();
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return uri;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            throw new Exception("请先授予存储权限后重试");
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
        File f = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.flush();
        fos.close();
        return Uri.fromFile(f);
    }

    private String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    /* ---------------- 服务器地址管理 ---------------- */

    private String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) return DEFAULT_URL;
        if (s.indexOf("://") < 0) s = "http://" + s;
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }

    private List<String> savedUrls() {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        String raw = prefs.getString(KEY_URLS, "");
        if (raw != null && raw.length() > 0) {
            for (String s : raw.split("\n")) {
                String u = normalizeUrl(s);
                if (u.length() > 0) set.add(u);
            }
        }
        if (set.isEmpty()) set.add(DEFAULT_URL);
        return new ArrayList<String>(set);
    }

    private void saveUrls(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(u);
        }
        prefs.edit().putString(KEY_URLS, sb.toString()).apply();
    }

    private void connect(String url) {
        currentUrl = normalizeUrl(url);
        List<String> urls = savedUrls();
        if (!urls.contains(currentUrl)) {
            urls.add(0, currentUrl);
            saveUrls(urls);
        }
        prefs.edit().putString(KEY_CUR, currentUrl).apply();
        reload();
    }

    private void reload() {
        errorView.setVisibility(View.GONE);
        web.loadUrl(currentUrl);
    }

    private void showFirstServerDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(DEFAULT_URL);
        et.setSelection(et.getText().length());
        et.setHint("如 http://192.168.5.132:8999 或 https://grades.example.com");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        TextView tip = new TextView(this);
        tip.setText("填写部署了「家庭事项记录」的私有服务器地址。\n家里用局域网 IP，出门可换域名 / 外网地址，随时可在顶部「服务器」里切换。\n支持 HTTP 内网明文与 HTTPS。");
        tip.setTextSize(13);
        tip.setTextColor(Color.parseColor("#475569"));
        tip.setPadding(0, 0, 0, dp(12));
        box.addView(tip);
        box.addView(et);
        new AlertDialog.Builder(this)
            .setTitle("连接私有服务器")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("连接", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    connect(et.getText().toString());
                }
            })
            .show();
    }

    private void showServerDialog() {
        final List<String> urls = savedUrls();
        final List<String> items = new ArrayList<String>();
        for (String u : urls) items.add((u.equals(currentUrl) ? "● " : "○ ") + u);
        items.add("＋ 添加服务器地址");
        items.add("－ 删除服务器地址");
        items.add("⟳ 清除登录状态并重新载入");
        new AlertDialog.Builder(this)
            .setTitle("服务器地址 · 当前 " + currentUrl)
            .setItems(items.toArray(new CharSequence[0]), new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    if (which < urls.size()) {
                        if (!urls.get(which).equals(currentUrl)) connect(urls.get(which));
                    } else if (which == urls.size()) {
                        showAddDialog();
                    } else if (which == urls.size() + 1) {
                        showDeleteDialog(urls);
                    } else {
                        WebStorage.getInstance().deleteAllData();
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        toast("已清除登录状态，请重新输入访问密码");
                        reload();
                    }
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private void showAddDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("http://192.168.5.132:8999 或 https://域名");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
            .setTitle("添加 / 连接新地址")
            .setView(box)
            .setPositiveButton("连接", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    String u = normalizeUrl(et.getText().toString());
                    Uri uri = Uri.parse(u);
                    if (uri.getHost() == null || uri.getHost().length() == 0) {
                        toast("地址不完整，请检查后重试");
                        return;
                    }
                    connect(u);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDeleteDialog(final List<String> urls) {
        new AlertDialog.Builder(this)
            .setTitle("删除已保存地址")
            .setItems(urls.toArray(new CharSequence[0]), new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    if (urls.size() <= 1) {
                        toast("至少保留一个服务器地址");
                        return;
                    }
                    String removed = urls.remove(which);
                    saveUrls(urls);
                    if (removed.equals(currentUrl)) connect(urls.get(0));
                    else toast("已删除 " + removed);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showMoreDialog() {
        final CharSequence[] items = {"回到首页", "重新载入当前页面", "清除缓存并重载", "关于这个 App"};
        new AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    switch (which) {
                        case 0: reload(); break;
                        case 1: web.reload(); break;
                        case 2:
                            web.clearCache(true);
                            CookieManager.getInstance().removeAllCookies(null);
                            reload();
                            toast("缓存已清除");
                            break;
                        default:
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("家庭事项记录 · 安卓客户端 v2.0.0")
                                .setMessage("原生 WebView 外壳，页面与数据全部来自私有服务器：\n" + currentUrl +
                                    "\n\n· 服务器地址可以随时在顶部「服务器」里切换（内网 / 外网）\n" +
                                    "· 登录状态保存在本机，换服务器后会要求重新输入访问密码\n" +
                                    "· 支持试卷 PDF / 图片上传、附件预览、CSV / JSON 导出（保存到「下载」目录）\n" +
                                    "· 打印成绩单请在电脑浏览器打开页面操作")
                                .setPositiveButton("知道了", null)
                                .show();
                            break;
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showError(String reason) {
        if (pageLoaded) return; // 页面已经打开，子资源失败不打断使用
        errorText.setText("地址：" + currentUrl + "\n\n" + reason +
            "\n\n请检查手机与 NAS 是否在同一局域网、端口 8999 是否正确、Docker 容器是否运行；也可以切换到外网地址。");
        errorView.setVisibility(View.VISIBLE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) results[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            super.onBackPressed();
        } else {
            lastBackTime = now;
            toast("再按一次返回键退出");
        }
    }
}
