package com.eink.weread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * 微信扫码登录：
 *  1. GET /r/weread-skills 收集 session cookie
 *  2. GET /api/auth/getLoginUid 取 uid，生成二维码
 *  3. 轮询 /api/auth/getLoginInfo?uid=&otp=（NEED_OTP 时输入手机上的 4 位验证码）
 *  4. 成功后保存 wr_vid / wr_skey / wr_rt
 */
public class LoginActivity extends Activity {

    private static final String REFERER = Const.SKILLS_PAGE_URL;

    private ImageView qrView;
    private TextView statusView;
    private volatile boolean cancelled = false;
    private volatile String otp = ""; // 手机端显示的 4 位验证码，空串表示无

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Session.init(this); // 必须先于任何网络请求：初始化 Cookie/证书信任库

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dip(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("用微信扫描二维码登录");
        title.setTextSize(18);
        title.setTextColor(0xFF111111);
        root.addView(title);

        qrView = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dip(320));
        qrView.setLayoutParams(lp);
        root.addView(qrView);

        statusView = new TextView(this);
        statusView.setText("正在获取二维码…");
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF444444);
        root.addView(statusView);

        setContentView(root);
        startLoginThread();
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startLoginThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. 打开 Skill 页收集 session cookie
                    Http.get(Const.SKILLS_PAGE_URL);

                    // 2. 获取登录 uid
                    Map<String, String> h = new java.util.HashMap<String, String>();
                    h.put("Referer", REFERER);
                    Http.Resp r = Http.get(Const.LOGIN_UID_URL, h);
                    if (!r.ok()) throw new Exception("getLoginUid HTTP " + r.code);
                    String uid = new JSONObject(r.body).getString("uid");

                    // 3. 展示二维码
                    final Bitmap qr = makeQr("https://weread.qq.com/web/confirm?uid="
                            + WeRead.urlencode(uid));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            qrView.setImageBitmap(qr);
                            statusView.setText("等待扫码…（请在手机微信中确认）");
                        }
                    });

                    // 4. 轮询登录结果，最长 300 秒
                    long deadline = System.currentTimeMillis() + 300_000L;
                    while (!cancelled && System.currentTimeMillis() < deadline) {
                        String url = Const.LOGIN_INFO_URL
                                + "?uid=" + WeRead.urlencode(uid)
                                + "&otp=" + WeRead.urlencode(otp);
                        Http.Resp pr;
                        try {
                            pr = Http.get(url, h);
                        } catch (Exception e) {
                            Thread.sleep(1500);
                            continue; // 网络抖动继续轮询
                        }
                        if (pr.body == null || pr.body.length() == 0) {
                            Thread.sleep(1500);
                            continue;
                        }
                        JSONObject j = new JSONObject(pr.body);
                        String logicCode = j.optString("logicCode", "");
                        if (j.optBoolean("succeed", false)) {
                            String vid = j.optString("webLoginVid", "0");
                            String token = j.optString("accessToken", "");
                            String refresh = j.optString("refreshToken", "");
                            Session.saveLogin(vid, token, refresh);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LoginActivity.this,
                                            "登录成功", Toast.LENGTH_SHORT).show();
                                    setResult(RESULT_OK);
                                    finish();
                                }
                            });
                            return;
                        } else if ("NEED_OTP".equals(logicCode) && otp.length() == 0) {
                            askOtp();
                        }
                        Thread.sleep(1500);
                    }
                    if (!cancelled) setStatus("二维码已过期，请退出重进。");
                } catch (final Exception e) {
                    if (!cancelled) setStatus("登录失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText(s);
            }
        });
    }

    /** NEED_OTP：弹出输入框要求填写手机端显示的 4 位验证码 */
    private void askOtp() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                statusView.setText("请在手机上输入 4 位验证码后确认");
                final EditText input = new EditText(LoginActivity.this);
                input.setTextSize(20);
                new AlertDialog.Builder(LoginActivity.this)
                        .setTitle("输入 4 位验证码")
                        .setView(input)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String v = input.getText().toString().trim();
                                if (v.matches("\\d{4}")) otp = v;
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private Bitmap makeQr(String content) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 480, 480, hints);
        Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }
}
