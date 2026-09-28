package io.agentflow.expense;

import io.agentflow.common.DomainException;

/**
 * 发票唯一键保留前导零，由可信查验返回的类型与号码确定，不使用文件名或图片哈希去重。
 * @author owlzhangfq@gmail.com
 */
public record InvoiceKey(Type type, String code, String number) {
    /** 数电票采用设计约定的二十位号码；传统号码的真伪与格式由查验服务确认。 */
    public InvoiceKey {
        if (type == null || number == null || !number.matches("[0-9]{1,32}")) throw invalid();
        if (type == Type.DIGITAL && (number.length() != 20 || code != null)) throw invalid();
        if (type == Type.TRADITIONAL && (code == null || !code.matches("[0-9]{1,32}"))) throw invalid();
    }

    /** 两类号码具有独立、无歧义的规范键。 */
    public String canonical() { return type == Type.DIGITAL ? "D:" + number : "T:" + code + ":" + number; }
    private static DomainException invalid() { return new DomainException("INVALID_INVOICE_KEY", "A verified invoice type and canonical number are required"); }

    /**
     * 当前发票身份的两种去重口径。
     * @author owlzhangfq@gmail.com
     */
    public enum Type { DIGITAL, TRADITIONAL }
}
