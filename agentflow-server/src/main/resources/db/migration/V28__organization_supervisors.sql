ALTER TABLE organization_appointment ADD COLUMN supervisor_appointment_id VARCHAR(36);
ALTER TABLE organization_unit ADD COLUMN head_appointment_id VARCHAR(36);

ALTER TABLE organization_appointment ADD CONSTRAINT fk_org_supervisor
    FOREIGN KEY (tenant_id, supervisor_appointment_id) REFERENCES organization_appointment(tenant_id, id);
ALTER TABLE organization_unit ADD CONSTRAINT fk_org_department_head
    FOREIGN KEY (tenant_id, head_appointment_id) REFERENCES organization_appointment(tenant_id, id);
