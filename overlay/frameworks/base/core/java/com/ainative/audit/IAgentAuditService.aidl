package com.ainative.audit;

import com.ainative.audit.AuditEvent;
import com.ainative.audit.AuditQuery;
import com.ainative.audit.ChainStatus;

/**
 * IAgentAuditService
 * AI Native OS 系统服务接口
 * 自动生成 - 请勿手动编辑
 */
interface IAgentAuditService {

    void logEvent(in AuditEvent event);

    List<AuditEvent> queryEvents(in AuditQuery query);

    ChainStatus verifyChain();

}