import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendTarget = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  build: {
    sourcemap: 'hidden',
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'vendor-vue', test: /node_modules[\\/](vue|@vue|vue-router)/ },
            { name: 'chunk-cashier', test: /Cashier|SupplierCashier|SupplierPayment|SupplierSettlement|SupplierReturn|SupplierAdjustment|PaymentBatch|PaymentCallback|PaymentDispute/ },
            { name: 'chunk-expense-assist', test: /ExpenseAgent|ExpenseHandling|ExpenseDraftAssist|ExpenseRisk|PrecheckExplanation|ExpenseConfiguration|ExpenseFinancialReport/ },
            { name: 'chunk-expense', test: /Expense|Precheck/ },
            { name: 'chunk-advance-procurement', test: /Advance|Repayment|Disbursement|Procurement|Budget|SupplierPayable/ },
            { name: 'chunk-voucher-invoice', test: /Voucher|Invoice|AccountMapping|Reversal/ },
            { name: 'chunk-designer', test: /Designer|FormSchema|Condition|Definition|PortableTemplate|TemplateCenter|BranchDiagnostics/ },
            { name: 'chunk-admin', test: /Organization|ApprovalProxy|BusinessCalendar|Audit|Webhook|Integration|SystemChecks|FirstWorkflow|ApiReference/ }
          ]
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: { '/api': { target: backendTarget, changeOrigin: false } }
  },
  preview: {
    port: 4173,
    proxy: { '/api': { target: backendTarget, changeOrigin: false } }
  }
})
