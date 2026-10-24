package com.bemodel.ontology.service;

import com.bemodel.common.BizException;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.ConceptParentMapper;
import com.bemodel.ontology.mapper.RelationMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class RelationQueryServiceTest {
    List<Concept> concepts = new ArrayList<>();
    List<Relation> relations = new ArrayList<>();
    // Mapper interfaces need no JVM instrumentation; any write or unexpected call fails the test.
    @SuppressWarnings("unchecked")
    static <T> T readOnlyMapper(Class<T> type, Supplier<List<?>> rows) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy,method,args) -> {
            if (method.getName().equals("selectList")) return rows.get();
            throw new AssertionError("Unexpected mapper call: " + method.getName());
        });
    }
    RelationQueryService service = new RelationQueryService(
            readOnlyMapper(ConceptMapper.class, () -> concepts),
            readOnlyMapper(RelationMapper.class, () -> relations),
            readOnlyMapper(ConceptParentMapper.class, List::of));
    Concept concept(String code){Concept c=new Concept();c.setCode(code);return c;}
    Relation rel(String from,String to,String name,int transitive){Relation r=new Relation();r.setFromConcept(from);r.setToConcept(to);r.setRelationName(name);r.setIsTransitive(transitive);return r;}
    void data(List<Relation> edges){concepts=List.of(concept("A"),concept("B"),concept("C"),concept("D"));relations=edges;}
    @Test void draftClosureWorksWithoutPublishingAndRetainsShortestPaths(){
        data(List.of(rel("A","B","属于",1),rel("B","C","属于",1),rel("A","D","属于",1),rel("D","C","属于",1),rel("C","A","属于",1)));
        Map<String,Object> result=service.preview("属于","A","closure","outgoing");
        assertEquals(3,result.get("count"));assertEquals(false,result.get("published"));assertEquals(true,result.get("semanticInference"));
        List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("reachable");
        assertEquals(2,rows.stream().filter(r->r.get("concept").equals("C")).findFirst().orElseThrow().get("depth"));
        assertEquals(List.of("A","B","C"),rows.stream().filter(r->r.get("concept").equals("C")).findFirst().orElseThrow().get("path"));
    }
    @Test void nonTransitiveRelationsHavePathsButNeverImplicitClosure(){
        data(List.of(rel("A","B","需要",0),rel("B","C","关联",0),rel("C","missing","关联",0)));
        assertThrows(BizException.class,()->service.preview("需要","A","closure","outgoing"));
        Map<String,Object> result=service.preview("*","A","paths","outgoing");assertEquals(2,result.get("count"));assertEquals(false,result.get("semanticInference"));
        assertEquals(2,service.preview("*","C","paths","incoming").get("count"));
    }
    @Test void unknownConceptRelationAndOptionsAreRejected(){
        data(List.of(rel("A","B","需要",0)));
        assertThrows(BizException.class,()->service.preview("*","Lot","paths","outgoing"));
        assertThrows(BizException.class,()->service.preview("missing","A","paths","outgoing"));
        assertThrows(BizException.class,()->service.preview("*","A","invented","outgoing"));
        assertThrows(BizException.class,()->service.preview("*","A","paths","both"));
    }
    @Test void depthCapIsExplicitAndDraftGraphIsReadOnly(){
        for(int i=0;i<15;i++)concepts.add(concept("N"+i));
        for(int i=0;i<14;i++)relations.add(rel("N"+i,"N"+(i+1),"包含",1));
        Map<String,Object> result=service.preview("包含","N0","closure","outgoing");
        assertEquals(12,result.get("count"));assertEquals(true,result.get("capped"));
        assertEquals("working",service.graph().get("scope"));assertEquals(false,service.graph().get("published"));
    }
}
