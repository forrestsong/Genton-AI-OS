package com.genton.audit;

import com.genton.audit.AuditEvent;
import com.genton.audit.AuditQuery;
import com.genton.audit.ChainStatus;

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