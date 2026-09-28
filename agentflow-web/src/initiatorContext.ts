/** 提交时冻结的本人任职，由服务端生成身份、组织名称及修订。@author owlzhangfq@gmail.com */
export interface InitiatorContext {
  appointmentId: string; personId: string; subject: string; directoryRevision: number
  legalEntityId: string; legalEntityName: string; departmentId: string; departmentName: string
  positionId: string; positionName: string
}

/** 本人任职的有界分页，后续页仍由服务端按当前账号授权。@author owlzhangfq@gmail.com */
export interface InitiatorAppointmentPage { items: InitiatorContext[]; nextAfterId?: string | null }

/** 组织名称来自当时快照，历史展示不再向当前目录取名称。 */
export function initiatorContextLabel(value: InitiatorContext) {
  return `${value.legalEntityName} · ${value.departmentName} / ${value.positionName}`
}
