package com.android.server.genton;

import android.content.Context;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 掩码引擎
 * 
 * 核心职责：
 * 1. 检测并掩码个人身份信息（PII）
 * 2. 支持多种 PII 类型（姓名/电话/身份证/银行卡/邮箱/地址）
 * 3. 保留部分信息用于识别（如电话号码保留后 4 位）
 * 4. 集成 C4.3 PII 掩码模型（命中率 >90% 验收）
 * 
 * PII 类型：
 * - PHONE: 手机号码（11 位）
 * - ID_CARD: 身份证号（15/18 位）
 * - BANK_CARD: 银行卡号（16-19 位）
 * - EMAIL: 电子邮箱
 * - NAME: 人名（需要 NER 模型）
 * - ADDRESS: 地址（需要 NER 模型）
 * 
 * 掩码策略：
 * - 手机号：保留后 4 位，如 138****1234
 * - 身份证：保留前 4 位和后 4 位，如 1234**********5678
 * - 银行卡：保留后 4 位，如 **** **** **** 1234
 * - 邮箱：保留首字母和域名，如 a****@example.com
 * - 姓名：全部替换为 [姓名]
 * - 地址：全部替换为 [地址]
 * 
 * 对应任务：A5.1（PII 掩码接入）
 * 前置依赖：C4.3（PII 掩码模型）
 * 草案来源：D1/D4
 * 
 * @author AI Native OS Team
 * @since A3 (智能与端云)
 */
public class PiiMaskingEngine {
    private static final String TAG = "PiiMaskingEngine";
    
    // 手机号正则（中国大陆 11 位）
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "1[3-9]\\d{9}"
    );
    
    // 身份证号正则（15/18 位）
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "\\d{15}|\\d{17}[\\dXx]"
    );
    
    // 银行卡号正则（16-19 位）
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
        "\\d{16,19}"
    );
    
    // 电子邮箱正则
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );
    
    private final Context mContext;
    
    // TODO: 集成 C4.3 PII 掩码模型（NER 识别姓名/地址）
    // private PiiNerModel mNerModel;
    
    public PiiMaskingEngine(Context context) {
        mContext = context;
        
        // TODO: 加载 NER 模型
        // mNerModel = loadNerModel();
        
        Slog.i(TAG, "PiiMaskingEngine initialized");
    }
    
    /**
     * 掩码 PII
     * 
     * @param text 原始文本
     * @return 掩码结果
     */
    public MaskResult maskPii(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskResult(text, false, new ArrayList<>());
        }
        
        String maskedText = text;
        List<PiiMatch> matches = new ArrayList<>();
        boolean hasPii = false;
        
        try {
            // 1. 掩码手机号
            MaskResult phoneResult = maskPhones(maskedText);
            maskedText = phoneResult.maskedText;
            matches.addAll(phoneResult.matches);
            hasPii |= phoneResult.hasPii;
            
            // 2. 掩码身份证号
            MaskResult idCardResult = maskIdCards(maskedText);
            maskedText = idCardResult.maskedText;
            matches.addAll(idCardResult.matches);
            hasPii |= idCardResult.hasPii;
            
            // 3. 掩码银行卡号
            MaskResult bankCardResult = maskBankCards(maskedText);
            maskedText = bankCardResult.maskedText;
            matches.addAll(bankCardResult.matches);
            hasPii |= bankCardResult.hasPii;
            
            // 4. 掩码电子邮箱
            MaskResult emailResult = maskEmails(maskedText);
            maskedText = emailResult.maskedText;
            matches.addAll(emailResult.matches);
            hasPii |= emailResult.hasPii;
            
            // 5. 掩码姓名（需要 NER 模型）
            // TODO: 集成 C4.3 NER 模型
            // MaskResult nameResult = maskNames(maskedText);
            // maskedText = nameResult.maskedText;
            // matches.addAll(nameResult.matches);
            // hasPii |= nameResult.hasPii;
            
            // 6. 掩码地址（需要 NER 模型）
            // TODO: 集成 C4.3 NER 模型
            // MaskResult addressResult = maskAddresses(maskedText);
            // maskedText = addressResult.maskedText;
            // matches.addAll(addressResult.matches);
            // hasPii |= addressResult.hasPii;
            
            if (hasPii) {
                Slog.d(TAG, String.format("PII masked: %d matches found", matches.size()));
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to mask PII", e);
            return new MaskResult(text, false, new ArrayList<>());
        }
        
        return new MaskResult(maskedText, hasPii, matches);
    }
    
    /**
     * 掩码手机号
     */
    private MaskResult maskPhones(String text) {
        List<PiiMatch> matches = new ArrayList<>();
        Matcher matcher = PHONE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String phone = matcher.group();
            String masked = maskPhone(phone);
            matcher.appendReplacement(sb, masked);
            
            matches.add(new PiiMatch("PHONE", phone, masked));
        }
        matcher.appendTail(sb);
        
        return new MaskResult(sb.toString(), !matches.isEmpty(), matches);
    }
    
    /**
     * 掩码单个手机号（保留后 4 位）
     */
    private String maskPhone(String phone) {
        if (phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
    
    /**
     * 掩码身份证号
     */
    private MaskResult maskIdCards(String text) {
        List<PiiMatch> matches = new ArrayList<>();
        Matcher matcher = ID_CARD_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String idCard = matcher.group();
            String masked = maskIdCard(idCard);
            matcher.appendReplacement(sb, masked);
            
            matches.add(new PiiMatch("ID_CARD", idCard, masked));
        }
        matcher.appendTail(sb);
        
        return new MaskResult(sb.toString(), !matches.isEmpty(), matches);
    }
    
    /**
     * 掩码单个身份证号（保留前 4 位和后 4 位）
     */
    private String maskIdCard(String idCard) {
        if (idCard.length() == 15) {
            return idCard.substring(0, 4) + "*******" + idCard.substring(11);
        } else if (idCard.length() == 18) {
            return idCard.substring(0, 4) + "**********" + idCard.substring(14);
        }
        return idCard;
    }
    
    /**
     * 掩码银行卡号
     */
    private MaskResult maskBankCards(String text) {
        List<PiiMatch> matches = new ArrayList<>();
        Matcher matcher = BANK_CARD_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String bankCard = matcher.group();
            
            // 排除可能的其他数字序列（简单启发式：银行卡号通常独立出现）
            if (!isLikelyBankCard(bankCard, matcher.start(), text)) {
                continue;
            }
            
            String masked = maskBankCard(bankCard);
            matcher.appendReplacement(sb, masked);
            
            matches.add(new PiiMatch("BANK_CARD", bankCard, masked));
        }
        matcher.appendTail(sb);
        
        return new MaskResult(sb.toString(), !matches.isEmpty(), matches);
    }
    
    /**
     * 判断是否可能是银行卡号
     */
    private boolean isLikelyBankCard(String number, int start, String text) {
        // 简单启发式：前后有空格或开头/结尾
        if (start == 0 || start == text.length() - number.length()) {
            return true;
        }
        
        char before = text.charAt(start - 1);
        char after = text.charAt(start + number.length());
        
        return Character.isWhitespace(before) && Character.isWhitespace(after);
    }
    
    /**
     * 掩码单个银行卡号（保留后 4 位）
     */
    private String maskBankCard(String bankCard) {
        if (bankCard.length() < 8) {
            return bankCard;
        }
        String last4 = bankCard.substring(bankCard.length() - 4);
        return "**** **** **** " + last4;
    }
    
    /**
     * 掩码电子邮箱
     */
    private MaskResult maskEmails(String text) {
        List<PiiMatch> matches = new ArrayList<>();
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String email = matcher.group();
            String masked = maskEmail(email);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
            
            matches.add(new PiiMatch("EMAIL", email, masked));
        }
        matcher.appendTail(sb);
        
        return new MaskResult(sb.toString(), !matches.isEmpty(), matches);
    }
    
    /**
     * 掩码单个电子邮箱（保留首字母和域名）
     */
    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return email;
        }
        
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        
        if (localPart.length() == 1) {
            return localPart + "****" + domain;
        }
        
        return localPart.charAt(0) + "****" + domain;
    }
    
    /**
     * 检查文本是否包含 PII（不掩码，仅检测）
     * 
     * @param text 待检测文本
     * @return true 如果包含 PII
     */
    public boolean containsPii(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        return PHONE_PATTERN.matcher(text).find()
            || ID_CARD_PATTERN.matcher(text).find()
            || BANK_CARD_PATTERN.matcher(text).find()
            || EMAIL_PATTERN.matcher(text).find();
    }
    
    // ========== 数据结构 ==========
    
    /**
     * 掩码结果
     */
    public static class MaskResult {
        public final String maskedText;
        public final boolean hasPii;
        public final List<PiiMatch> matches;
        
        public MaskResult(String maskedText, boolean hasPii, List<PiiMatch> matches) {
            this.maskedText = maskedText;
            this.hasPii = hasPii;
            this.matches = matches;
        }
    }
    
    /**
     * PII 匹配记录
     */
    public static class PiiMatch {
        public final String type;       // PII 类型
        public final String original;   // 原始值（不持久化）
        public final String masked;     // 掩码值
        
        public PiiMatch(String type, String original, String masked) {
            this.type = type;
            this.original = original;
            this.masked = masked;
        }
    }
}
