package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.BusinessHours;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The chat supervisor's report (kltn-docs 16 §3 Output "Chỉ số", BR-09): per agent and for everyone, over the
 * conversations opened in a period - how fast the first answer came, in business hours, as the target is counted; how
 * many first answers were late; how long resolving took; and the customers' ratings.
 *
 * <p>Counted here rather than in SQL because the first-response time is business time ({@link BusinessHours#between}),
 * which a query cannot count without the calendar. A period is bounded ({@link #MAX_DAYS}, {@link #MAX_ROWS}), so a
 * report is never a full scan of history.</p>
 */
@Service
@Transactional(readOnly = true)
public class ChatReports {

    static final int MAX_DAYS = 366;
    static final int MAX_ROWS = 20_000;

    /**
     * One agent's figures, or everyone's ({@code agentId} null).
     *
     * @param conversations                 conversations opened in the period and assigned to the agent
     * @param answered                      of those, how many had their first answer
     * @param averageFirstResponseMinutes   the mean time to the first answer, business minutes; null when none
     * @param firstResponsesOverdue         first answers that were late (the supervisors were alerted)
     * @param resolved                      how many were resolved
     * @param averageResolutionHours        the mean time from opening to resolution, wall-clock hours; null when none
     * @param csatAverage                   the mean rating, 1-5; null when none
     * @param csatDistribution              how many ratings of each star, "1" to "5"
     */
    public record AgentFigures(UUID agentId, String agentName, long conversations, long answered,
                               Double averageFirstResponseMinutes, long firstResponsesOverdue, long resolved,
                               Double averageResolutionHours, long csatCount, Double csatAverage,
                               Map<String, Long> csatDistribution) {
    }

    /** The report: the period, everyone together, and each agent. */
    public record Report(LocalDate from, LocalDate to, AgentFigures total, List<AgentFigures> agents) {
    }

    private final ChatCallers callers;
    private final ConversationRepository conversations;
    private final AgentDirectory agents;
    private final ChatProperties properties;

    public ChatReports(ChatCallers callers, ConversationRepository conversations, AgentDirectory agents,
                       ChatProperties properties) {
        this.callers = callers;
        this.conversations = conversations;
        this.agents = agents;
        this.properties = properties;
    }

    /**
     * @param from the first day, in business time
     * @param to   the last day, included
     */
    public Report report(CurrentUser user, LocalDate from, LocalDate to, UUID agentId) {
        callers.staff(user);
        if (from == null || to == null || to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A period of 1 to " + MAX_DAYS + " days");
        }
        BusinessHours hours = properties.businessHours();
        Instant start = from.atStartOfDay(hours.zone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(hours.zone()).toInstant();
        List<ConversationRepository.ConversationFacts> facts = conversations.openedBetween(start, end, agentId,
                MAX_ROWS);
        Map<UUID, List<ConversationRepository.ConversationFacts>> byAgent = new LinkedHashMap<>();
        facts.stream().filter(f -> f.agentId() != null)
                .forEach(f -> byAgent.computeIfAbsent(f.agentId(), k -> new ArrayList<>()).add(f));
        Map<UUID, String> names = agents.names(byAgent.keySet());
        List<AgentFigures> rows = byAgent.entrySet().stream()
                .map(e -> figures(e.getKey(), names.get(e.getKey()), e.getValue(), hours))
                .sorted((a, b) -> Long.compare(b.conversations(), a.conversations())).toList();
        return new Report(from, to, figures(null, null, facts, hours), rows);
    }

    static AgentFigures figures(UUID agentId, String name, List<ConversationRepository.ConversationFacts> facts,
                                BusinessHours hours) {
        long answered = 0;
        double firstResponse = 0;
        long overdue = 0;
        long resolved = 0;
        double resolution = 0;
        long rated = 0;
        double stars = 0;
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            distribution.put(Integer.toString(i), 0L);
        }
        for (ConversationRepository.ConversationFacts f : facts) {
            if (f.firstRespondedAt() != null) {
                answered++;
                firstResponse += hours.between(f.createdAt(), f.firstRespondedAt()).toSeconds() / 60.0;
            }
            if (f.firstResponseAlertedAt() != null) {
                overdue++;
            }
            if (f.resolvedAt() != null) {
                resolved++;
                resolution += Duration.between(f.createdAt(), f.resolvedAt()).toSeconds() / 3600.0;
            }
            if (f.csatScore() != null) {
                rated++;
                stars += f.csatScore();
                distribution.merge(Integer.toString(f.csatScore()), 1L, Long::sum);
            }
        }
        return new AgentFigures(agentId, name, facts.size(), answered, answered == 0 ? null : round(firstResponse
                / answered), overdue, resolved, resolved == 0 ? null : round(resolution / resolved), rated,
                rated == 0 ? null : round(stars / rated), distribution);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
