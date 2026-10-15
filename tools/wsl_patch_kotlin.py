#!/usr/bin/env python3
import sys

BASE = '/root/aosp/frameworks/base/core/java/com/ainative'
ok = True

def patch(path, old, new, label):
    global ok
    full = BASE + path
    with open(full) as f:
        c = f.read()
    if old not in c:
        print(f"ERROR [{label}]: block not found")
        ok = False
        return
    c = c.replace(old, new, 1)
    with open(full, 'w') as f:
        f.write(c)
    print(f"OK [{label}]")

# 1. IntentLearningGenerator.kt: remove bad PolicyRule import
patch('/learn/IntentLearningGenerator.kt',
      'import com.ainative.policy.PolicyRule\n',
      '',
      '1. remove PolicyRule import')

# 2. EntityExtractionPipeline.kt: fix shouldMerge call
patch('/semantics/extraction/EntityExtractionPipeline.kt',
'''        for (entity in sameTypeEntities) {
            val mergeDecision = EntityMergeHeuristics.shouldMerge(
                entity1 = entity,
                entity2Attributes = parseAttributes(request.attributes),
                entity2Type = request.type
            )
''',
'''        for (entity in sameTypeEntities) {
            val now = System.currentTimeMillis()
            val candidate = Entity(
                entityId = EntityId.generate(request.type),
                type = request.type,
                displayName = request.displayName,
                attributes = request.attributes,
                provenance = request.provenance,
                confidence = request.confidence,
                createdAtMs = now,
                updatedAtMs = now
            )
            val mergeDecision = EntityMergeHeuristics.shouldMerge(
                entity1 = entity,
                entity2 = candidate
            )
''',
'2. fix shouldMerge call')

# 3. ContextPackingStrategy.kt: serialize fixes
patch('/semantics/packing/ContextPackingStrategy.kt',
      'put("id", entity.entityId.value)',
      'put("id", entity.entityId.id)',
      '3a. entityId.value -> id')

patch('/semantics/packing/ContextPackingStrategy.kt',
      'put("id", relation.relationId.value)',
      'put("id", relation.relationId)',
      '3b. relationId.value -> relationId')

patch('/semantics/packing/ContextPackingStrategy.kt',
      'put("type", relation.type.typeName)',
      'put("type", relation.type.relationName)',
      '3c. typeName -> relationName')

patch('/semantics/packing/ContextPackingStrategy.kt',
      'put("source", relation.sourceEntityId.value)',
      'put("source", relation.sourceEntityId.id)',
      '3d. sourceEntityId.value -> id')

patch('/semantics/packing/ContextPackingStrategy.kt',
      'put("target", relation.targetEntityId.value)',
      'put("target", relation.targetEntityId.id)',
      '3e. targetEntityId.value -> id')

patch('/semantics/packing/ContextPackingStrategy.kt',
      'put("strength", relation.strength)',
      'put("strength", relation.confidence)',
      '3f. strength -> confidence')

# deserialize: EntityType.fromTypeName -> fromName with default
patch('/semantics/packing/ContextPackingStrategy.kt',
      'type = EntityType.fromTypeName(entityJson.getString("type")),',
      'type = EntityType.fromName(entityJson.getString("type")) ?: EntityType.ENTITY,',
      '3g. EntityType.fromTypeName -> fromName')

# deserialize: Relation constructor
patch('/semantics/packing/ContextPackingStrategy.kt',
'''            val relation = Relation(
                relationId = RelationId(relationJson.getString("id")),
                type = RelationType.fromTypeName(relationJson.getString("type")),
                sourceEntityId = EntityId(relationJson.getString("source")),
                targetEntityId = EntityId(relationJson.getString("target")),
                strength = relationJson.getDouble("strength").toFloat(),
                provenance = "checkpoint",
                createdAtMs = 0L,
                isDeleted = false
            )''',
'''            val relation = Relation(
                relationId = relationJson.getString("id"),
                type = RelationType.fromName(relationJson.getString("type")) ?: RelationType.RELATED_TO,
                sourceEntityId = EntityId(relationJson.getString("source")),
                targetEntityId = EntityId(relationJson.getString("target")),
                attributes = "{}",
                provenance = "checkpoint",
                confidence = relationJson.getDouble("strength").toFloat(),
                createdAtMs = 0L,
                isDeleted = false
            )''',
'3h. Relation constructor fix')

# 4. ContextPackingService.kt: sorted() + CACHE_TTL_MS
patch('/semantics/packing/ContextPackingService.kt',
      'return "${request.seedEntityIds.sorted().joinToString(",")}_" +',
      'return "${request.seedEntityIds.map { it.id }.sorted().joinToString(",")}_" +',
      '4a. seedEntityIds.sorted fix')

patch('/semantics/packing/ContextPackingService.kt',
      'private const val CACHE_TTL_MS = 60000L               // 缓存有效期 1 分钟',
      'const val CACHE_TTL_MS = 60000L               // 缓存有效期 1 分钟',
      '4b. CACHE_TTL_MS not private')

# 5. LearnActivationValidator.kt: String? reason
patch('/learn/LearnActivationValidator.kt',
      '                reason = compilationResult.reason',
      '                reason = compilationResult.reason ?: "DSL compliance check failed"',
      '5. String? reason fix')

if not ok:
    print("SOME PATCHES FAILED")
    sys.exit(1)
print("ALL PATCHES APPLIED OK")
