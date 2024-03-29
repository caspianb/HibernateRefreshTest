package com.logicalbias.jpa;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.logicalbias.jpa.entity.Child;
import com.logicalbias.jpa.entity.Gender;
import com.logicalbias.jpa.entity.Parent;
import com.logicalbias.jpa.entity.TestEntity;

@Slf4j
public class HibernateTests {

    private final EntityManager em;

    HibernateTests() {
        this.em = HibernateTools.getEntityManager();
        log.info("****************************** TEST START ******************************");
    }

    @AfterEach
    void testEnd() {
        try {
            log.info("Flushing changes before rollback...");
            em.flush();
        }
        catch (Exception e) {
            log.error("Exception detected on flush: ", e);
        }
        log.info("******************************* TEST END *******************************");
        em.clear();
        em.getTransaction().rollback();
        em.close();
        HibernateTools.getEntityManagerFactory().close();
    }

    @Test
    void testEnumConversion() {
        Set<Integer> parentIds = createTestData(2, 2);
        Integer parentId = parentIds.iterator().next();
        em.clear();

        em.createNativeQuery("update parent SET gender = 'm' where 1=1").executeUpdate();

        Parent parent = em.find(Parent.class, parentId);
        Assertions.assertEquals(Gender.MALE, parent.getGender());

        parent.setGender(Gender.UNKNOWN);
        em.flush();
        em.clear();

        parent = em.find(Parent.class, parentId);
        Assertions.assertEquals(Gender.UNKNOWN, parent.getGender());
    }

    @Test
    void testReKeyChildrenCollectionViaDelete() {
        Set<Integer> parentIds = createTestData(1, 5);
        em.clear();

        int parentId = parentIds.iterator().next();
        Parent parent = em.find(Parent.class, parentId);
        List<Child> children = new ArrayList<>(parent.getChildrenEager());

        int firstChildId = children.get(0).getChildId();

        log.info("DELETING ENTITIES");
        children.forEach(em::remove);

        // Required to force deletes to sync to DB now
        // otherwise persists below might encounter duplicate ID exceptions
        em.flush();

        log.info("CHANGING KEYS AND REINSERTING");
        // Update IDs of all children objects and re-merge into session
        children.stream()
                .peek(child -> child.setChildId(child.getChildId() + 1))
                .forEach(em::persist);

        log.info("CREATING NEW ELEMENT AT FRONT OF LIST");
        // Add new child at front of list
        Child newChild = new Child();
        newChild.setChildId(firstChildId);
        newChild.setName("First");
        newChild.setAge(23);
        newChild.setParent(parent);

        em.persist(newChild);
        children.add(0, newChild);

        // Force persist inserts to database prior to refreshing parent com.logicalbias.jpa.entity
        em.flush();
        em.refresh(parent);

        // Our lists shold contain equal elements still
        Assertions.assertEquals(children, parent.getChildrenEager());

        // Not only that, if we kept them in sync they should be the EXACT SAME com.logicalbias.jpa.entity objects
        for (int i = 0; i < children.size(); i++) {
            Assertions.assertSame(children.get(i), parent.getChildrenEager().get(i));
        }
    }

    @Test
    void testReKeyChildrenCollectionViaEvict() {
        Set<Integer> parentIds = createTestData(1, 5);
        em.clear();

        int parentId = parentIds.iterator().next();
        Parent parent = em.find(Parent.class, parentId);
        List<Child> children = new ArrayList<>(parent.getChildrenEager());

        int firstChildId = children.get(0).getChildId();

        log.info("DETACHING ENTITIES");
        children.forEach(em::detach);

        log.info("CHANGING KEYS AND MERGING");
        // Update IDs of all children objects and re-merge into session
        List<Child> mergedList = children.stream()
                .peek(child -> child.setChildId(child.getChildId() + 1))
                .map(em::merge)
                .collect(Collectors.toList());

        log.info("CREATING NEW ELEMENT AT FRONT OF LIST");
        // Add new child at front of list
        Child newChild = new Child();
        newChild.setChildId(firstChildId);
        newChild.setName("First");
        newChild.setAge(23);
        newChild.setParent(parent);

        children.add(0, newChild);

        Child mergedChild = em.merge(newChild);
        mergedList.add(0, mergedChild);

        // Force updates/inserts to database prior to refreshing parent com.logicalbias.jpa.entity
        em.flush();
        em.refresh(parent);

        // Both lists should contain "equal" elements still
        Assertions.assertEquals(children, parent.getChildrenEager());
        Assertions.assertEquals(mergedList, parent.getChildrenEager());

        // However, the entities we started with are NOT the same entities after the merge!
        // This is because merge would have created new persistent entities for us.
        for (int i = 0; i < children.size(); i++) {
            Assertions.assertNotSame(children.get(i), parent.getChildrenEager().get(i));
        }

        // This is why our MERGED LIST is the same objects we get off the parent com.logicalbias.jpa.entity.
        for (int i = 0; i < mergedList.size(); i++) {
            Assertions.assertSame(mergedList.get(i), parent.getChildrenEager().get(i));
        }
    }

    @Test
    void testHibernateMerge() {
        // Put some test data into the database and clear the session
        Set<Integer> parentIds = createTestData(1, 3);
        em.clear();

        int parentId = parentIds.iterator().next();
        Parent parent = em.find(Parent.class, parentId);
        Set<Child> children = parent.getChildrenLazy();

        // Create new unattached children objects with different names
        List<Child> copies = children.stream()
                .map(child -> {
                    Child copy = new Child(child);
                    copy.setName("John" + child.getChildId());
                    return copy;
                })
                .toList();

        parent.getChildrenEager().stream().map(Child::toString).forEach(log::info);

        log.info("Merging copies into session...");
        copies.forEach(em::merge);

        log.info("Merge complete.");
        parent.getChildrenEager().stream().map(Child::toString).forEach(log::info);
    }

    @Test
    void testManuallySetIdIsNotOverriddenByGeneratedValue() {
        var testId = UUID.randomUUID();
        log.info("MANUALLY GENERATED TEST ID: {}", testId);

        var entity = new TestEntity().setId(testId);
        em.merge(entity);

        em.flush();
        em.clear();

        var persistedId = entity.getId();
        Assertions.assertEquals(testId, persistedId);

        entity = em.find(TestEntity.class, testId);
        Assertions.assertNotNull(entity);
    }

    // ************************************************************************
    // Helpers to create test data defined below
    // ************************************************************************

    Set<Integer> createTestData(int numParents, int childrenPerParent) {
        log.info("******************** CREATE TEST DATA ********************");
        Set<Integer> parentIds = new LinkedHashSet<>();

        //
        // Prep some data in the DB
        //
        for (int parentId = 0; parentId < numParents; parentId++) {
            Parent parent = createParent(parentId, "Parent_" + parentId);
            parentIds.add(parentId);

            // Create some children for each parent...
            for (int i = 0; i < childrenPerParent; i++) {
                int childId = parentId * childrenPerParent + i;
                createChild(childId, "Child_" + parentId + "_" + i, 15, parent);
            }
        }

        log.info("******************** DONE TEST DATA ********************");
        return parentIds;
    }

    Parent createParent(int parentId, String name) {
        Parent parent = new Parent();
        parent.setParentId(parentId);
        parent.setName(name);

        em.persist(parent);
        em.flush();
        return parent;
    }

    Child createChild(int childId, String name, int age, Parent parent) {
        Child child = new Child();
        child.setChildId(childId);
        child.setName(name);
        child.setAge(age);
        child.setParent(parent);

        em.persist(child);
        em.flush();
        return child;
    }

}
