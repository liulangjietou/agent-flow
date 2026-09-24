import type { ApplicationSearchFilters } from './applicationSearch'
import { WorkbookExportQuery } from './workbookExport.js'
export { workbookType } from './workbookExport.js'

/** 申请导出使用已提交筛选，不允许分页。@author owlzhangfq@gmail.com */
export type ApplicationExportFilters = Omit<ApplicationSearchFilters, 'cursor' | 'limit'>
export const ApplicationExportQuery = WorkbookExportQuery<ApplicationExportFilters>
