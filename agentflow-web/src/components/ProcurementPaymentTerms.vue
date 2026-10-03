<script setup lang="ts">
import type { ProcurementContent, ProcurementRound } from '../procurementPayment'
import { moneyLabel } from '../expenses'
defineProps<{ content: ProcurementContent; financial?: ProcurementRound | null }>()
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN')
</script>

<template>
  <div class="procurement-terms" aria-label="采购付款依据">
    <dl class="terms-facts">
      <div><dt>本次付款额</dt><dd class="terms-amount">{{ moneyLabel(content.amount) }}</dd></div>
      <div><dt>原应付编号</dt><dd>{{ content.payableReference }}</dd></div>
      <div><dt>供应商</dt><dd>{{ financial?.payable.supplierName ?? '保存后预检读取名称' }}<small>{{ content.supplierReference }}</small></dd></div>
      <div v-if="financial"><dt>付款法人</dt><dd>{{ financial.legalEntity.name }}</dd></div>
      <div v-if="financial"><dt>本轮供应商收款账户</dt><dd>{{ financial.payable.maskedAccount }}</dd></div>
      <div v-if="financial"><dt>原应付到期日</dt><dd>{{ financial.payable.dueOn }}</dd></div>
    </dl>
    <div class="terms-purpose"><h4>付款用途</h4><p>{{ content.purpose }}</p></div>
    <template v-if="financial">
      <dl class="payable-balances">
        <div><dt>原应付含税额</dt><dd>{{ moneyLabel(financial.payable.gross) }}</dd></div>
        <div><dt>原应付已结金额</dt><dd>{{ moneyLabel(financial.payable.settled) }}</dd></div>
        <div><dt>查询时未结余额</dt><dd>{{ moneyLabel(financial.payable.outstanding) }}</dd></div>
      </dl>
      <div class="terms-references">
        <h4>原采购与财务凭据</h4>
        <dl class="reference-grid">
          <div><dt>合同</dt><dd>{{ financial.payable.contractReference }}</dd></div>
          <div><dt>采购订单</dt><dd>{{ financial.payable.orderReference }}</dd></div>
          <div><dt>三单匹配</dt><dd>{{ financial.payable.matchingReference }}</dd></div>
          <div><dt>原挂账凭证</dt><dd>{{ financial.payable.accrualVoucherReference }}</dd></div>
          <div><dt>原预算确认</dt><dd>{{ financial.payable.budgetRecognitionReference }}</dd></div>
        </dl>
      </div>
      <section class="matched-lines" aria-label="订单验收发票匹配明细">
        <h4>订单 · 验收 · 发票 <span>{{ financial.payable.lines.length }} 行</span></h4>
        <article v-for="line in financial.payable.lines" :key="line.lineNo" class="matched-line">
          <div class="line-heading"><strong>明细 {{ line.lineNo }} · 订单第 {{ line.orderLineNo }} 行</strong><span>数量单位：{{ line.unit }}</span></div>
          <div class="matching-comparison">
            <div><h5>订单</h5><strong>{{ line.orderedQuantity }}</strong><span>{{ moneyLabel(line.orderedGross) }}</span></div>
            <div><h5>已验收</h5><strong>{{ line.acceptedQuantity }}</strong><span>{{ moneyLabel(line.acceptedGross) }}</span></div>
            <div><h5>已开票</h5><strong>{{ line.invoicedQuantity }}</strong><span>{{ moneyLabel(line.invoicedGross) }}</span></div>
          </div>
          <dl class="reference-grid line-references">
            <div><dt>验收单</dt><dd>{{ line.acceptanceReference }}</dd></div>
            <div><dt>{{ line.invoice.type === 'DIGITAL' ? '数电发票' : '发票代码 / 号码' }} · 第 {{ line.invoiceLineNo }} 行</dt><dd>{{ line.invoice.code ? `${line.invoice.code} / ` : '' }}{{ line.invoice.number }}</dd></div>
            <div><dt>发票税额</dt><dd>{{ moneyLabel(line.tax) }}</dd></div>
          </dl>
        </article>
      </section>
      <p class="terms-note">依据版本 {{ financial.payable.sourceVersion }} · 查询于 {{ dateLabel(financial.payable.observedAt) }}。以上是本轮核对依据，实际付款前需重新核对原应付余额与供应商账户。</p>
    </template>
  </div>
</template>

<style scoped>
.procurement-terms{margin:18px 0;border:1px solid var(--line);border-radius:12px;overflow:hidden;min-width:0}.terms-facts,.payable-balances{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));margin:0;background:var(--paper)}.terms-facts>div,.payable-balances>div{padding:18px;min-width:0}.procurement-terms dt,.procurement-terms h4{font-size:11px;color:var(--muted);font-weight:400;margin:0 0 9px}.procurement-terms dd{font-size:13px;margin:0;line-height:1.8;overflow-wrap:anywhere}.terms-facts dd small{display:block;color:var(--muted);font-size:11px}.terms-facts .terms-amount{font:500 18px 'DM Mono',monospace;color:var(--deep)}.terms-purpose,.terms-references,.matched-lines{padding:18px;border-top:1px solid var(--line)}.terms-purpose p{font-size:13px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.payable-balances{grid-template-columns:repeat(3,minmax(0,1fr));border-top:1px solid var(--line);background:#f1faf7}.payable-balances dd{font:12px 'DM Mono',monospace;line-height:1.8}.reference-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin:0}.reference-grid>div{min-width:0}.matched-lines h4{display:flex;justify-content:space-between;gap:10px}.matched-line{border:1px solid var(--line);border-radius:8px;overflow:hidden;margin-top:14px}.line-heading{display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;padding:13px;font-size:11px;background:var(--paper);line-height:1.7}.line-heading span{color:var(--muted)}.matching-comparison{display:grid;grid-template-columns:repeat(3,minmax(0,1fr))}.matching-comparison>div{padding:14px 12px;min-width:0}.matching-comparison h5{font-size:11px;color:var(--muted);margin:0 0 8px;font-weight:400}.matching-comparison strong,.matching-comparison span{display:block;font:11px 'DM Mono',monospace;line-height:1.8;overflow-wrap:anywhere}.matching-comparison strong{font-size:13px;color:var(--deep);margin-bottom:6px}.line-references{padding:14px;border-top:1px solid var(--line)}.line-references dd{font-size:11px}.terms-note{font-size:11px;line-height:1.9;color:var(--muted);margin:0;padding:0 18px 18px;overflow-wrap:anywhere}@media(max-width:550px){.terms-facts,.reference-grid{grid-template-columns:1fr}.terms-facts>div{padding:14px 18px}.payable-balances{grid-template-columns:1fr}.payable-balances>div{padding:12px 18px}.matching-comparison>div{padding:12px 8px}.matching-comparison strong{font-size:11px}}
</style>
