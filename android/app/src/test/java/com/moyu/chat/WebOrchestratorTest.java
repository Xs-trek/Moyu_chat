package com.moyu.chat;

import static org.junit.Assert.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

public class WebOrchestratorTest {
    private static final ZonedDateTime NOW = ZonedDateTime.of(
            2026, 9, 1, 21, 30, 0, 0, ZoneId.of("Asia/Singapore"));

    @Test public void pureDeviceDateDoesNotBecomeWebFreshnessRequest() {
        WebOrchestrator.Plan plan = WebOrchestrator.legacyPlan(
                "今天是几号？", ChatTurnOptions.WEB_AUTO, MoyuWebService.FollowUpContext.none(), NOW);
        assertFalse(plan.useWeb);
        assertEquals(WebOrchestrator.INTENT_NONE, plan.intent);
    }

    @Test public void yesterdayNewsGetsExactDeviceLocalPublicationWindow() {
        WebOrchestrator.Plan plan = WebOrchestrator.legacyPlan(
                "查一下昨天 OpenAI 发生了什么新闻", ChatTurnOptions.WEB_AUTO,
                MoyuWebService.FollowUpContext.none(), NOW);
        assertTrue(plan.useWeb);
        assertEquals(WebOrchestrator.FRESHNESS_DAY, plan.freshness);
        assertEquals("news", plan.category);
        assertEquals("2026-08-30T16:00:00Z", plan.startPublishedDate);
        assertEquals("2026-08-31T16:00:00Z", plan.endPublishedDate);
        assertFalse(plan.queries.isEmpty());
    }

    @Test public void latestVersionUsesFreshCacheBoundaryInsteadOfUnboundedSearch() {
        WebOrchestrator.Plan plan = WebOrchestrator.legacyPlan(
                "检索一下最新版本", ChatTurnOptions.WEB_AUTO,
                MoyuWebService.FollowUpContext.none(), NOW);
        assertTrue(plan.useWeb);
        assertEquals(WebOrchestrator.FRESHNESS_DAY, plan.freshness);
        assertEquals(24, plan.maxAgeHours());
    }

    @Test public void explicitUrlIsSeparatedFromNaturalLanguageFetchInstruction() {
        WebOrchestrator.Plan plan = WebOrchestrator.legacyPlan(
                "请抓取 https://example.com/page 并总结内容", ChatTurnOptions.WEB_AUTO,
                MoyuWebService.FollowUpContext.none(), NOW);
        assertTrue(plan.useWeb);
        assertEquals(WebOrchestrator.INTENT_URL, plan.intent);
        assertEquals(1, plan.urls.size());
        assertEquals("https://example.com/page", plan.urls.get(0));
        assertTrue("host fetch should not turn surrounding prose into a search query", plan.queries.isEmpty());
    }

    @Test public void plannerOutputIsBoundedToThreeQueriesAndTypedFreshness() {
        WebOrchestrator.Plan plan = WebOrchestrator.parseModelPlan(
                "prefix {\"useWeb\":true,\"intent\":\"research\",\"queries\":[\"a\",\"b\",\"c\",\"d\"],"
                        + "\"freshness\":\"week\",\"category\":\"news\",\"startPublishedDate\":\"2026-08-25\","
                        + "\"endPublishedDate\":\"2026-09-01\",\"fetchTopResults\":true} suffix");
        assertNotNull(plan);
        assertTrue(plan.useWeb);
        assertEquals(3, plan.queries.size());
        assertEquals(WebOrchestrator.FRESHNESS_WEEK, plan.freshness);
        assertEquals("news", plan.category);
        assertTrue(plan.fetchTopResults);
    }

    @Test public void autoPlannerIsSemanticFallbackNotOnlyKeywordTrigger() {
        WebOrchestrator orchestrator = new WebOrchestrator();
        assertTrue("changing public facts should reach semantic planning even without a literal search verb",
                orchestrator.shouldRunPlanner(ChatTurnOptions.WEB_AUTO,
                        "Who is the CEO of Example Corp?", MoyuWebService.FollowUpContext.none()));
        assertFalse("ordinary stable explanatory text should not pay a planner call by default",
                orchestrator.shouldRunPlanner(ChatTurnOptions.WEB_AUTO,
                        "explain pointers", MoyuWebService.FollowUpContext.none()));
    }
}
