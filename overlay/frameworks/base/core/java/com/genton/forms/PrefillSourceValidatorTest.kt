package com.genton.forms

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * C6.3 预填源规则化 - 单元测试
 * 
 * 测试覆盖：
 * 1. secret 字段强制 NONE
 * 2. password 格式字段禁止预填
 * 3. S4_ENTITY 阶段门控（A3 期生效）
 * 4. WEB/APP 敏感字段禁用
 * 5. S4 置信度门槛 ≥0.6
 * 6. 预填源降级链
 * 7. 系统阶段切换
 */
class PrefillSourceValidatorTest {
    
    @Before
    fun setUp() {
        // 每个测试前重置为 A2 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A2)
    }
    
    // ==================== 测试 1: secret 字段强制 NONE ====================
    
    @Test
    fun testSecretFieldForcedNone() {
        val field = FieldSchema(
            name = "api_key",
            type = "string",
            format = null,
            title = "API Key",
            required = true,
            xSecret = true,
            xPrefillSource = PrefillSource.USER_MEMORY.name  // 声明 USER_MEMORY
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.USER_MEMORY)
        
        // 校验失败，强制为 NONE
        assertFalse("Secret field should fail validation", result.isValid)
        assertEquals("Effective source should be NONE", PrefillSource.NONE, result.effectiveSource)
        assertNotNull("Should have failure reason", result.reason)
        assertTrue("Reason should mention security", result.reason!!.contains("security", ignoreCase = true))
    }
    
    @Test
    fun testSecretFieldWithNoneIsValid() {
        val field = FieldSchema(
            name = "api_key",
            type = "string",
            format = null,
            title = "API Key",
            required = true,
            xSecret = true,
            xPrefillSource = PrefillSource.NONE.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.NONE)
        
        // 校验通过
        assertTrue("Secret field with NONE should be valid", result.isValid)
        assertEquals("Effective source should be NONE", PrefillSource.NONE, result.effectiveSource)
    }
    
    // ==================== 测试 2: password 格式字段禁止预填 ====================
    
    @Test
    fun testPasswordFormatFieldForcedNone() {
        val field = FieldSchema(
            name = "password",
            type = "string",
            format = "password",
            title = "Password",
            required = true,
            xSecret = false,  // 即使 xSecret=false，format=password 也禁预填
            xPrefillSource = PrefillSource.USER_TYPED.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.USER_TYPED)
        
        // 校验失败，强制为 NONE
        assertFalse("Password field should fail validation", result.isValid)
        assertEquals("Effective source should be NONE", PrefillSource.NONE, result.effectiveSource)
        assertTrue("Reason should mention password", result.reason!!.contains("password", ignoreCase = true))
    }
    
    // ==================== 测试 3: S4_ENTITY 阶段门控 ====================
    
    @Test
    fun testS4EntityNotAvailableInA2() {
        // 当前为 A2 阶段
        val field = FieldSchema(
            name = "email",
            type = "string",
            format = "email",
            title = "Email",
            required = true,
            xPrefillSource = PrefillSource.S4_ENTITY.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.S4_ENTITY)
        
        // 校验失败（A2 期不支持 S4_ENTITY）
        assertFalse("S4_ENTITY should not be available in A2", result.isValid)
        assertEquals("Effective source should be NONE", PrefillSource.NONE, result.effectiveSource)
        assertTrue("Reason should mention phase requirement", result.reason!!.contains("A3", ignoreCase = true))
    }
    
    @Test
    fun testS4EntityAvailableInA3() {
        // 切换到 A3 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A3)
        
        val field = FieldSchema(
            name = "email",
            type = "string",
            format = "email",
            title = "Email",
            required = true,
            xPrefillSource = PrefillSource.S4_ENTITY.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.S4_ENTITY)
        
        // 校验通过（A3 期支持 S4_ENTITY）
        assertTrue("S4_ENTITY should be available in A3", result.isValid)
        assertEquals("Effective source should be S4_ENTITY", PrefillSource.S4_ENTITY, result.effectiveSource)
    }
    
    @Test
    fun testIsPrefillSourceAvailableByPhase() {
        // A2 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A2)
        assertTrue(PrefillSourceValidator.isPrefillSourceAvailable(PrefillSource.USER_TYPED))
        assertTrue(PrefillSourceValidator.isPrefillSourceAvailable(PrefillSource.USER_MEMORY))
        assertFalse(PrefillSourceValidator.isPrefillSourceAvailable(PrefillSource.S4_ENTITY))
        assertTrue(PrefillSourceValidator.isPrefillSourceAvailable(PrefillSource.WEB))
        assertTrue(PrefillSourceValidator.isPrefillSourceAvailable(PrefillSource.APP))
        
        // A3 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A3)
        assertTrue(PrefillSourceValidator.isPrefillSourceAvailable(PrefillSource.S4_ENTITY))
    }
    
    // ==================== 测试 4: WEB/APP 敏感字段禁用 ====================
    
    @Test
    fun testWebPrefillBlockedForSensitiveField() {
        val field = FieldSchema(
            name = "credit_card_number",
            type = "string",
            format = null,
            title = "Credit Card",
            required = true,
            xSecret = false,  // 非 xSecret，但字段名敏感
            xPrefillSource = PrefillSource.WEB.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.WEB)
        
        // 校验失败（敏感字段禁用 WEB 预填）
        assertFalse("WEB should be blocked for sensitive field", result.isValid)
        assertEquals("Effective source should be NONE", PrefillSource.NONE, result.effectiveSource)
        assertTrue("Reason should mention sensitive fields", result.reason!!.contains("sensitive", ignoreCase = true))
    }
    
    @Test
    fun testAppPrefillBlockedForSensitiveField() {
        val field = FieldSchema(
            name = "ssn",  // Social Security Number
            type = "string",
            format = null,
            title = "SSN",
            required = true,
            xSecret = false,
            xPrefillSource = PrefillSource.APP.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.APP)
        
        // 校验失败（敏感字段禁用 APP 预填）
        assertFalse("APP should be blocked for sensitive field", result.isValid)
        assertEquals("Effective source should be NONE", PrefillSource.NONE, result.effectiveSource)
    }
    
    @Test
    fun testWebPrefillAllowedForNormalField() {
        val field = FieldSchema(
            name = "city",
            type = "string",
            format = null,
            title = "City",
            required = false,
            xSecret = false,
            xPrefillSource = PrefillSource.WEB.name
        )
        
        val result = PrefillSourceValidator.validate(field, PrefillSource.WEB)
        
        // 校验通过（普通字段允许 WEB 预填）
        assertTrue("WEB should be allowed for normal field", result.isValid)
        assertEquals("Effective source should be WEB", PrefillSource.WEB, result.effectiveSource)
    }
    
    // ==================== 测试 5: S4 置信度门槛 ====================
    
    @Test
    fun testS4ConfidenceThreshold() {
        // 高于门槛
        assertTrue("0.6 should pass threshold", PrefillSourceValidator.validateS4Confidence(0.6f))
        assertTrue("0.8 should pass threshold", PrefillSourceValidator.validateS4Confidence(0.8f))
        assertTrue("1.0 should pass threshold", PrefillSourceValidator.validateS4Confidence(1.0f))
        
        // 低于门槛
        assertFalse("0.59 should fail threshold", PrefillSourceValidator.validateS4Confidence(0.59f))
        assertFalse("0.5 should fail threshold", PrefillSourceValidator.validateS4Confidence(0.5f))
        assertFalse("0.0 should fail threshold", PrefillSourceValidator.validateS4Confidence(0.0f))
    }
    
    // ==================== 测试 6: 预填源降级链 ====================
    
    @Test
    fun testPrefillDegradationChain() {
        val field = FieldSchema(
            name = "email",
            type = "string",
            format = "email",
            title = "Email",
            required = true,
            xSecret = false,
            xPrefillSource = PrefillSource.USER_MEMORY.name
        )
        
        // A2 阶段：USER_MEMORY 降级链应包含 USER_TYPED, USER_MEMORY, APP（排除 S4_ENTITY 和 WEB）
        val chain = PrefillSourceValidator.getPrefillDegradationChain(PrefillSource.USER_MEMORY, field)
        
        assertTrue("Chain should contain USER_TYPED", chain.contains(PrefillSource.USER_TYPED))
        assertTrue("Chain should contain USER_MEMORY", chain.contains(PrefillSource.USER_MEMORY))
        assertFalse("Chain should not contain S4_ENTITY in A2", chain.contains(PrefillSource.S4_ENTITY))
        
        // 优先级降序
        val priorities = chain.map { it.priority }
        assertEquals("Chain should be sorted by priority descending", priorities, priorities.sortedDescending())
    }
    
    @Test
    fun testPrefillDegradationChainInA3() {
        // 切换到 A3 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A3)
        
        val field = FieldSchema(
            name = "email",
            type = "string",
            format = "email",
            title = "Email",
            required = true,
            xSecret = false,
            xPrefillSource = PrefillSource.USER_MEMORY.name
        )
        
        // A3 阶段：降级链应包含 S4_ENTITY
        val chain = PrefillSourceValidator.getPrefillDegradationChain(PrefillSource.USER_MEMORY, field)
        
        assertTrue("Chain should contain S4_ENTITY in A3", chain.contains(PrefillSource.S4_ENTITY))
    }
    
    @Test
    fun testPrefillDegradationChainForSecretField() {
        val field = FieldSchema(
            name = "password",
            type = "string",
            format = "password",
            title = "Password",
            required = true,
            xSecret = true,
            xPrefillSource = PrefillSource.USER_TYPED.name
        )
        
        // secret 字段降级链应只包含 NONE
        val chain = PrefillSourceValidator.getPrefillDegradationChain(PrefillSource.USER_TYPED, field)
        
        assertEquals("Chain should only contain NONE for secret field", listOf(PrefillSource.NONE), chain)
    }
    
    // ==================== 测试 7: 批量校验 ====================
    
    @Test
    fun testValidateForm() {
        val schema = FormSchema(
            meta = FormMeta(
                formId = "test.form",
                formVersion = "1.0.0",
                formTitle = "Test Form",
                submitMode = SubmitMode.IMMEDIATE,
                validateOnChange = false,
                validateOnSubmit = true,
                provenance = "test",
                signatureHash = null
            ),
            fields = listOf(
                FieldSchema(
                    name = "email",
                    type = "string",
                    format = "email",
                    title = "Email",
                    required = true,
                    xPrefillSource = PrefillSource.USER_MEMORY.name
                ),
                FieldSchema(
                    name = "password",
                    type = "string",
                    format = "password",
                    title = "Password",
                    required = true,
                    xSecret = true,
                    xPrefillSource = PrefillSource.USER_TYPED.name  // 非法：secret 字段不能预填
                ),
                FieldSchema(
                    name = "city",
                    type = "string",
                    format = null,
                    title = "City",
                    required = false,
                    xPrefillSource = PrefillSource.WEB.name
                )
            )
        )
        
        val results = PrefillSourceValidator.validateForm(schema)
        
        assertEquals("Should validate 3 fields", 3, results.size)
        
        // email: 通过
        assertTrue("email should be valid", results["email"]!!.isValid)
        assertEquals(PrefillSource.USER_MEMORY, results["email"]!!.effectiveSource)
        
        // password: 失败（secret 字段禁预填）
        assertFalse("password should be invalid", results["password"]!!.isValid)
        assertEquals(PrefillSource.NONE, results["password"]!!.effectiveSource)
        
        // city: 通过
        assertTrue("city should be valid", results["city"]!!.isValid)
        assertEquals(PrefillSource.WEB, results["city"]!!.effectiveSource)
    }
    
    // ==================== 测试 8: getEffectivePrefillSource ====================
    
    @Test
    fun testGetEffectivePrefillSource() {
        // 普通字段：有效源 = 声明源
        val normalField = FieldSchema(
            name = "email",
            type = "string",
            format = "email",
            title = "Email",
            required = true,
            xPrefillSource = PrefillSource.USER_MEMORY.name
        )
        assertEquals(PrefillSource.USER_MEMORY, PrefillSourceValidator.getEffectivePrefillSource(normalField))
        
        // secret 字段：有效源 = NONE
        val secretField = FieldSchema(
            name = "api_key",
            type = "string",
            format = null,
            title = "API Key",
            required = true,
            xSecret = true,
            xPrefillSource = PrefillSource.USER_MEMORY.name
        )
        assertEquals(PrefillSource.NONE, PrefillSourceValidator.getEffectivePrefillSource(secretField))
    }
    
    // ==================== 测试 9: getAvailablePrefillSources ====================
    
    @Test
    fun testGetAvailablePrefillSources() {
        // A2 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A2)
        val sourcesA2 = PrefillSourceValidator.getAvailablePrefillSources()
        
        assertTrue(sourcesA2.contains(PrefillSource.USER_TYPED))
        assertTrue(sourcesA2.contains(PrefillSource.USER_MEMORY))
        assertFalse(sourcesA2.contains(PrefillSource.S4_ENTITY))
        assertTrue(sourcesA2.contains(PrefillSource.WEB))
        assertTrue(sourcesA2.contains(PrefillSource.APP))
        assertTrue(sourcesA2.contains(PrefillSource.NONE))
        
        // 优先级降序
        val priorities = sourcesA2.map { it.priority }
        assertEquals(priorities, priorities.sortedDescending())
        
        // A3 阶段
        PrefillSourceValidator.setSystemPhase(PrefillSourceValidator.SystemPhase.A3)
        val sourcesA3 = PrefillSourceValidator.getAvailablePrefillSources()
        
        assertTrue(sourcesA3.contains(PrefillSource.S4_ENTITY))
    }
    
    // ==================== 测试 10: 优先级合法性 ====================
    
    @Test
    fun testPriorityValidation() {
        val field = FieldSchema(
            name = "test",
            type = "string",
            format = null,
            title = "Test",
            required = false,
            xPrefillSource = PrefillSource.USER_TYPED.name
        )
        
        // 合法优先级（0-10）
        val validSource = PrefillSource.USER_TYPED
        assertTrue("Valid priority should pass", validSource.priority in 0..10)
        
        val result = PrefillSourceValidator.validate(field, validSource)
        assertTrue("Valid priority should be accepted", result.isValid)
    }
}
