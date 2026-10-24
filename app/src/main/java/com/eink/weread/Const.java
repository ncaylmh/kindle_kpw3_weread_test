package com.eink.weread;

import java.nio.charset.Charset;

final class Const {
    static final Charset UTF8 = Charset.forName("UTF-8");

    static final String BASE = "https://weread.qq.com";
    static final String SKILLS_PAGE_URL = BASE + "/r/weread-skills";
    static final String LOGIN_UID_URL = BASE + "/api/auth/getLoginUid";
    static final String LOGIN_INFO_URL = BASE + "/api/auth/getLoginInfo";

    static final String PREFS = "weread";
    static final String K_WR_VID = "wr_vid";
    static final String K_WR_SKEY = "wr_skey";
    static final String K_WR_RT = "wr_rt";
    static final String K_API_KEY = "api_key";

    private Const() {}
}
