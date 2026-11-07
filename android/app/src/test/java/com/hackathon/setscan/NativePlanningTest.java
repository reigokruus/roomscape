package com.hackathon.setscan;

import org.junit.Test;
import java.nio.FloatBuffer;
import static org.junit.Assert.*;

/** Behavioral tests of the actual Java engines used by the native app, without a fake AR session. */
public class NativePlanningTest {
    private Lighting.Light light() {
        Lighting.Light l = new Lighting.Light();
        l.name = "Test panel"; l.type = Lighting.Type.PANEL; l.beamDeg = 60; l.dimmer = 1;
        return l;
    }
    @Test public void disabledLightAddsNoLux() {
        Lighting.Light l = light(); l.z = 1; l.on = false;
        assertEquals(0,Lighting.luxFrom(l,null,0,0,0,0,0,1),0);
    }
    @Test public void illuminationFollowsInverseSquareDistance() {
        Lighting.Light l = light(); l.z = 1;
        float near = Lighting.luxFrom(l,null,0,0,0,0,0,1);
        l.z = 2;
        assertEquals(3000,near,0.001);
        assertEquals(near/4,Lighting.luxFrom(l,null,0,0,0,0,0,1),0.001);
    }
    @Test public void lightPointedAwayFromSubjectDoesNotIlluminateIt() {
        Lighting.Light l = light();
        assertEquals(0,Lighting.luxFrom(l,new float[]{0,0,1},0,0,-1,0,0,1),0);
    }
    @Test public void roomLightIsPreservedAndExposureOffsetDoublesBrightness() {
        Lighting l = new Lighting();
        assertEquals(1,l.exposure(),0.0001);
        l.exposureEv = 1;
        assertEquals(2,l.exposure(),0.0001);
    }
    @Test public void faceExposureCompensatesWhenGearLightIsAdded() {
        Lighting l = new Lighting();
        l.props.add(new Lighting.Prop(Lighting.PropKind.PERSON_STANDING,0,0,0));
        Lighting.Light panel = light(); panel.y = 1.62f; panel.z = 1; l.lights.add(panel);
        assertTrue(l.exposure() > 0 && l.exposure() < 1);
        assertTrue(l.readout().contains("Face:"));
    }
    @Test public void matchingLightAndWhiteBalanceIsNeutral() {
        assertArrayEquals(new float[]{1,1,1},Lighting.tint(5600,5600),0.0001f);
    }
    @Test public void subjectSelectionPrefersPersonAndUsesWorldFloorHeight() {
        Lighting l = new Lighting(); l.floorY = -1.4f;
        l.props.add(new Lighting.Prop(Lighting.PropKind.TABLE,4,5,0));
        l.props.add(new Lighting.Prop(Lighting.PropKind.PERSON_SEATED,1,2,0));
        assertArrayEquals(new float[]{1,-0.22f,2},l.subjectFace(),0.0001f);
    }
    @Test public void defaultLightingSetupHasSubjectAndWorkingLights() {
        Lighting l = new Lighting(); l.defaultSetup(null,0,0,-1);
        assertNotNull(l.subject()); assertEquals(2,l.lights.size());
        assertTrue(Float.isFinite(l.exposure())); assertTrue(l.exposure() > 0);
        assertFalse(l.readout().contains("NaN"));
    }
    private ShotPlanner analysedRoom() {
        FloatBuffer points = FloatBuffer.allocate((41*41+4)*3);
        for (int x=0;x<=40;x++) for (int z=0;z<=40;z++) points.put(x*0.1f-2).put(0).put(z*0.1f-2);
        points.put(-2).put(3).put(-2); points.put(2).put(3).put(-2);
        points.put(2).put(3).put(2); points.put(-2).put(3).put(2);
        points.flip(); ShotPlanner p = new ShotPlanner(); p.analyse(points,41*41+4); return p;
    }
    @Test public void sparseScanIsNotDeclaredReadyForPlanning() {
        ShotPlanner p = new ShotPlanner(); p.analyse(FloatBuffer.allocate(30),10);
        assertFalse(p.isReady());
    }
    @Test public void scannedFloorProducesFiniteCameraAndLightsForAllSceneTypes() {
        ShotPlanner p = analysedRoom(); assertTrue(p.isReady());
        float[] subject = p.defaultSubject();
        assertEquals(0,subject[0],0.2); assertEquals(0,subject[2],0.2);
        for (ShotPlanner.Scene scene:ShotPlanner.Scene.values()) {
            ShotPlanner.Plan plan = p.plan(scene,subject);
            assertFalse(plan.elements.isEmpty());
            assertTrue(plan.elements.stream().anyMatch(e -> e.isCamera));
            for (ShotPlanner.Element e:plan.elements) assertTrue(Float.isFinite(e.x) && Float.isFinite(e.y) && Float.isFinite(e.z));
        }
    }
    @Test public void productPlanUsesTappedTableHeight() {
        ShotPlanner p = analysedRoom();
        assertEquals(0.83f,p.plan(ShotPlanner.Scene.PRODUCT,new float[]{0,0.75f,0}).sy,0.0001);
    }
}
