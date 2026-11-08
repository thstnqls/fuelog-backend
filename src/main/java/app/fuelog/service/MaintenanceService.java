package app.fuelog.service;

import app.fuelog.dto.MaintenanceDtos.*;
import app.fuelog.repository.MaintenanceRepository;
import app.fuelog.repository.MaintenanceRepository.*;
import app.fuelog.repository.PairingRepository;

import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 공동·개인 정비 권한을 검사하고 확정 이력으로 다음 정비 예정을 계산한다. */
@Service
public class MaintenanceService {
    private static final Duration ODOMETER_FRESHNESS = Duration.ofDays(7);
    private final MaintenanceRepository repo;
    private final InstallationAccess access;
    private final InstallationAuth auth;
    private final PairingRepository pairing;
    private final ObjectMapper json;
    private final Validator validator;

    public MaintenanceService(
            MaintenanceRepository repo,
            InstallationAuth auth,
            PairingRepository pairing,
            ObjectMapper json,
            Validator validator,
            InstallationAccess access) {
        this.access = access;
        this.repo = repo;
        this.auth = auth;
        this.pairing = pairing;
        this.json = json;
        this.validator = validator;
    }

    @Transactional
    public Summary summary(String bearer) {
        UUID vehicle = installationVehicle(bearer);
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul"));
        Sample sample = repo.latest(vehicle);
        boolean fresh =
                sample != null && !sample.observedAt().isBefore(now.minus(ODOMETER_FRESHNESS));
        Odometer odometer =
                sample == null
                        ? new Odometer(null, null, "unavailable")
                        : new Odometer(
                                sample.km(),
                                sample.observedAt().toEpochMilli(),
                                fresh ? "available" : "stale");
        var items =
                repo.plans(vehicle).stream()
                        .filter(p -> p.active() && p.shared())
                        .filter(p -> p.basis() == Basis.USER || verifiedEvidence(p))
                        .map(
                                p -> {
                                    Boolean dateDue =
                                            p.dueDate() == null
                                                    ? null
                                                    : !today.isBefore(p.dueDate());
                                    Boolean distanceDue =
                                            !fresh || p.dueKm() == null
                                                    ? null
                                                    : sample.km().compareTo(p.dueKm()) >= 0;
                                    BigDecimal remaining =
                                            !fresh || p.dueKm() == null
                                                    ? null
                                                    : p.dueKm()
                                                            .subtract(sample.km())
                                                            .max(BigDecimal.ZERO);
                                    return new SummaryItem(
                                            p.id(),
                                            p.name(),
                                            p.lastDate(),
                                            p.lastKm(),
                                            p.dueDate(),
                                            p.dueKm(),
                                            p.basis(),
                                            dateDue,
                                            distanceDue,
                                            remaining,
                                            p.km(),
                                            p.months());
                                })
                        .sorted(
                                Comparator.comparingInt(
                                                (SummaryItem p) ->
                                                        Boolean.TRUE.equals(p.dateDue())
                                                                        || Boolean.TRUE.equals(
                                                                                p.distanceDue())
                                                                ? 0
                                                                : 1)
                                        .thenComparing(
                                                p ->
                                                        Boolean.TRUE.equals(p.dateDue())
                                                                ? p.nextDueDate()
                                                                : LocalDate.MAX)
                                        .thenComparing(SummaryItem::name)
                                        .thenComparing(p -> p.id().toString()))
                        .limit(2)
                        .toList();
        UUID editor = access.currentUser(auth.require(bearer).id());
        boolean canEdit = editor != null && "OWNER".equals(repo.role(vehicle, editor));
        return new Summary(repo.revision(vehicle), odometer, items, canEdit, canEdit ? editor : null);
    }

    @Transactional
    public OdometerSaved odometer(String bearer, UUID sampleId, OdometerInput input) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        access.requireCollection(installation);
        UUID vehicle = linked(installation);
        repo.lockVehicle(vehicle);
        OdometerInput b =
                new OdometerInput(
                        number(input.km()),
                        input.observedAt(),
                        input.unit(),
                        input.source(),
                        input.quality());
        validateBean(b);
        Instant observed = Instant.ofEpochMilli(b.observedAt());
        if (observed.isAfter(Instant.now().plusSeconds(60)))
            fail(HttpStatus.BAD_REQUEST, "INVALID_OBSERVED_AT");
        if ((b.quality() == Quality.VALID) != (b.km() != null))
            fail(HttpStatus.BAD_REQUEST, "ODOMETER_QUALITY_MISMATCH");
        byte[] hash = hash(b);
        Sample previous = repo.sample(installation, sampleId);
        if (previous != null) {
            if (!previous.vehicle().equals(vehicle)) fail(HttpStatus.FORBIDDEN, "VEHICLE_MISMATCH");
            if (!MessageDigest.isEqual(hash, previous.hash()))
                fail(HttpStatus.CONFLICT, "SAMPLE_ID_REUSED");
            return new OdometerSaved(sampleId, previous.receivedAt());
        }
        if (b.quality() == Quality.VALID && !repo.monotonic(vehicle, b.km(), observed))
            fail(HttpStatus.UNPROCESSABLE_CONTENT, "ODOMETER_NON_MONOTONIC");
        Instant now = now();
        repo.saveSample(installation, vehicle, sampleId, b, hash, now);
        repo.bump(vehicle);
        return new OdometerSaved(sampleId, now);
    }

    @Transactional
    public Plans vehiclePlans(String bearer) {
        var owner = deviceOwner(bearer);
        return new Plans(repo.plans(owner.vehicle()).stream().filter(p -> p.shared() && p.active()).map(this::view).toList());
    }

    @Transactional
    public Plan putVehiclePlan(String bearer, UUID item, String ifMatch, VehiclePlanInput b) {
        var owner = deviceOwner(bearer);
        validateBean(b);
        if (!b.plan().shared()) fail(HttpStatus.BAD_REQUEST, "VEHICLE_PLAN_MUST_BE_SHARED");
        PlanRow prior = repo.plan(owner.vehicle(), item);
        if (prior != null && !prior.shared()) fail(HttpStatus.FORBIDDEN, "PLAN_NOT_SHARED");
        putPlan(owner.user(), owner.vehicle(), item, ifMatch, b.plan());
        PlanRow current = repo.plan(owner.vehicle(), item);
        if (b.lastServiceDate() != null && current.lastDate() != null && b.lastServiceDate().isBefore(current.lastDate()))
            fail(HttpStatus.BAD_REQUEST, "CORRECT_PAST_SERVICE_IN_MOBILE_HISTORY");
        if ((b.lastServiceDate() != null || b.lastServiceKm() != null)
                && (!Objects.equals(b.lastServiceDate(), current.lastDate())
                || !Objects.equals(number(b.lastServiceKm()), number(current.lastKm())))) {
            create(owner.user(), owner.vehicle(), b.operationId(), new EventInput(item, b.lastServiceDate(),
                    b.lastServiceKm(), Status.CONFIRMED, true, false, null, null, null, null, null));
        }
        return view(repo.plan(owner.vehicle(), item));
    }

    @Transactional
    public Deleted deleteVehiclePlan(String bearer, UUID item, String ifMatch) {
        var owner = deviceOwner(bearer);
        if (!requirePlan(owner.vehicle(), item).shared()) fail(HttpStatus.FORBIDDEN, "PLAN_NOT_SHARED");
        return deletePlan(owner.user(), owner.vehicle(), item, ifMatch);
    }

    private DeviceOwner deviceOwner(String bearer) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        access.requireCollection(installation);
        UUID vehicle = linked(installation);
        repo.lockVehicle(vehicle);
        UUID user = access.currentUser(installation);
        if (user == null) fail(HttpStatus.FORBIDDEN, "VERIFIED_OWNER_REQUIRED");
        ownerRole(repo.role(vehicle, user));
        return new DeviceOwner(user, vehicle);
    }
    private record DeviceOwner(UUID user, UUID vehicle) {}

    @Transactional
    public Plans plans(UUID user, UUID vehicle) {
        member(user, vehicle);
        return new Plans(
                repo.plans(vehicle).stream()
                        .filter(p -> visible(p, user))
                        .map(this::view)
                        .toList());
    }

    @Transactional
    public Plan putPlan(UUID user, UUID vehicle, UUID item, String ifMatch, PlanInput b) {
        String role = member(user, vehicle);
        validateBean(b);
        PlanRow prior = repo.plan(vehicle, item);
        if (prior != null) {
            writablePlan(prior, user, role);
            match(ifMatch, prior.revision());
            if (prior.shared() != b.shared() && !prior.owner().equals(user))
                fail(HttpStatus.FORBIDDEN, "PLAN_OWNER_REQUIRED");
        } else if (ifMatch != null && expected(ifMatch) != 0)
            fail(HttpStatus.PRECONDITION_FAILED, "REVISION_MISMATCH");
        if (b.shared()) ownerRole(role);
        if (b.intervalKm() == null && b.intervalMonths() == null)
            fail(HttpStatus.BAD_REQUEST, "MAINTENANCE_CYCLE_REQUIRED");
        if (b.basis() == Basis.USER && b.evidenceId() != null)
            fail(HttpStatus.BAD_REQUEST, "USER_BASIS_HAS_EVIDENCE");
        if (b.basis() == Basis.MANUFACTURER) {
            Evidence evidence =
                    repo.evidence(
                            vehicle, b.evidenceId(), b.name(), b.intervalKm(), b.intervalMonths());
            if (evidence == null || !evidence.verified())
                fail(HttpStatus.UNPROCESSABLE_CONTENT, "MANUFACTURER_EVIDENCE_UNVERIFIED");
        }
        repo.savePlan(
                vehicle,
                item,
                user,
                b,
                prior == null ? 1 : prior.revision() + 1,
                prior == null,
                now());
        recalculate(vehicle, item);
        repo.bump(vehicle);
        return view(repo.plan(vehicle, item));
    }

    @Transactional
    public Deleted deletePlan(UUID user, UUID vehicle, UUID item, String ifMatch) {
        String role = member(user, vehicle);
        PlanRow p = requirePlan(vehicle, item);
        writablePlan(p, user, role);
        if (!p.active() && expected(ifMatch) == p.revision() - 1)
            return new Deleted(item, p.revision(), true);
        match(ifMatch, p.revision());
        if (p.active()) {
            repo.disablePlan(item, p.revision() + 1, now());
            repo.bump(vehicle);
        }
        return new Deleted(item, repo.plan(vehicle, item).revision(), true);
    }

    @Transactional
    public Events events(UUID user, UUID vehicle, UUID cursor) {
        member(user, vehicle);
        var rows = repo.events(vehicle, user, cursor);
        boolean more = rows.size() > 50;
        var page = rows.stream().limit(50).map(e -> eventView(e, user)).toList();
        return new Events(page, more ? page.getLast().id() : null);
    }

    @Transactional
    public Event create(UUID user, UUID vehicle, String key, EventInput input) {
        String role = member(user, vehicle);
        if (key == null || key.isBlank() || key.length() > 100)
            fail(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
        EventInput b = canonical(input);
        validateEvent(b);
        byte[] hash = hash(b);
        EventRow previous = repo.forKey(vehicle, user, key);
        if (previous != null) {
            if (!MessageDigest.isEqual(hash, previous.createHash()))
                fail(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
            return json.readValue(previous.createResponse(), Event.class);
        }
        writableItem(b, user, vehicle, role);
        UUID id = UUID.randomUUID();
        String payload = json.writeValueAsString(b);
        Event response = eventView(id, 1, b, true);
        Instant now = now();
        repo.createEvent(
                id, vehicle, user, b, payload, key, hash, json.writeValueAsString(response), now);
        repo.audit(id, user, "CREATE", 1, null, payload, now);
        recalculate(vehicle, b.itemId());
        repo.bump(vehicle);
        return response;
    }

    @Transactional
    public Event patch(UUID user, UUID vehicle, UUID eventId, String ifMatch, JsonNode patch) {
        String role = member(user, vehicle);
        EventRow prior = requireEvent(vehicle, eventId);
        if (!prior.author().equals(user)) fail(HttpStatus.FORBIDDEN, "EVENT_AUTHOR_REQUIRED");
        if (prior.deletedAt() != null) fail(HttpStatus.GONE, "EVENT_DELETED");
        EventInput original = parse(prior);
        if (original.shared()) ownerRole(role);
        match(ifMatch, prior.revision());
        if (patch == null || !patch.isObject() || patch.isEmpty())
            fail(HttpStatus.BAD_REQUEST, "INVALID_PATCH");
        ObjectNode merged = (ObjectNode) json.valueToTree(original);
        for (var field : patch.properties()) {
            if (!merged.has(field.getKey())) fail(HttpStatus.BAD_REQUEST, "UNKNOWN_PATCH_FIELD");
            merged.set(field.getKey(), field.getValue());
        }
        EventInput b;
        try {
            b = canonical(json.treeToValue(merged, EventInput.class));
        } catch (tools.jackson.core.JacksonException ex) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_PATCH");
            return null;
        }
        validateEvent(b);
        writableItem(b, user, vehicle, role);
        long revision = prior.revision() + 1;
        String payload = json.writeValueAsString(b);
        Instant now = now();
        repo.updateEvent(eventId, b, payload, revision, now);
        repo.audit(eventId, user, "UPDATE", revision, prior.payload(), payload, now);
        recalculate(vehicle, prior.item());
        if (!prior.item().equals(b.itemId())) recalculate(vehicle, b.itemId());
        repo.bump(vehicle);
        return eventView(eventId, revision, b, true);
    }

    @Transactional
    public Deleted deleteEvent(UUID user, UUID vehicle, UUID eventId, String ifMatch) {
        String role = member(user, vehicle);
        EventRow prior = requireEvent(vehicle, eventId);
        if (!prior.author().equals(user)) fail(HttpStatus.FORBIDDEN, "EVENT_AUTHOR_REQUIRED");
        if (parse(prior).shared()) ownerRole(role);
        if (prior.deletedAt() != null && expected(ifMatch) == prior.revision() - 1)
            return new Deleted(eventId, prior.revision(), true);
        match(ifMatch, prior.revision());
        if (prior.deletedAt() == null) {
            Instant now = now();
            repo.deleteEvent(eventId, prior.revision() + 1, now);
            repo.audit(eventId, user, "DELETE", prior.revision() + 1, prior.payload(), null, now);
            recalculate(vehicle, prior.item());
            repo.bump(vehicle);
        }
        return new Deleted(eventId, repo.event(vehicle, eventId).revision(), true);
    }

    // 방문 추정은 제외하고 공개 범위에 맞는 마지막 확정 이력으로 다음 정비를 계산한다.
    private void recalculate(UUID vehicle, UUID item) {
        PlanRow p = requirePlan(vehicle, item);
        var latest =
                repo.itemEvents(vehicle, item).stream()
                        .filter(
                                e -> {
                                    var b = parse(e);
                                    return b.shared()
                                            || !p.shared() && p.owner().equals(e.author());
                                })
                        .max(
                                Comparator.comparing(
                                                (EventRow e) ->
                                                        parse(e).serviceDate() == null
                                                                ? LocalDate.ofInstant(
                                                                        e.createdAt(),
                                                                        ZoneOffset.UTC)
                                                                : parse(e).serviceDate())
                                        .thenComparing(EventRow::createdAt)
                                        .thenComparing(e -> e.id().toString()))
                        .orElse(null);
        EventInput last = latest == null ? null : parse(latest);
        LocalDate lastDate = last == null ? null : last.serviceDate();
        BigDecimal lastKm = last == null ? null : last.odometerKm();
        LocalDate dueDate =
                lastDate == null || p.months() == null ? null : lastDate.plusMonths(p.months());
        BigDecimal dueKm = lastKm == null || p.km() == null ? null : lastKm.add(p.km());
        repo.due(item, lastDate, lastKm, dueDate, dueKm);
    }

    private UUID installationVehicle(String bearer) {
        UUID installation = auth.require(bearer).id();
        pairing.lockInstallation(installation);
        UUID vehicle = linked(installation);
        repo.lockVehicle(vehicle);
        return vehicle;
    }

    private UUID linked(UUID installation) {
        UUID vehicle = pairing.linkedVehicle(installation);
        if (vehicle == null) fail(HttpStatus.FORBIDDEN, "INSTALLATION_NOT_LINKED");
        return vehicle;
    }

    private String member(UUID user, UUID vehicle) {
        repo.lockVehicle(vehicle);
        String role = repo.role(vehicle, user);
        if (role == null) fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        return role;
    }

    private static void ownerRole(String role) {
        if (!"OWNER".equals(role)) fail(HttpStatus.FORBIDDEN, "VEHICLE_OWNER_REQUIRED");
    }

    private static boolean visible(PlanRow p, UUID user) {
        return p.shared() || p.owner().equals(user);
    }

    private static void writablePlan(PlanRow p, UUID user, String role) {
        if (p.shared()) ownerRole(role);
        else if (!p.owner().equals(user)) fail(HttpStatus.FORBIDDEN, "PLAN_OWNER_REQUIRED");
    }

    private void writableItem(EventInput b, UUID user, UUID vehicle, String role) {
        PlanRow p = requirePlan(vehicle, b.itemId());
        if (!p.active() || !visible(p, user)) fail(HttpStatus.FORBIDDEN, "PLAN_UNAVAILABLE");
        if (b.shared()) {
            ownerRole(role);
            if (!p.shared()) fail(HttpStatus.FORBIDDEN, "PLAN_NOT_SHARED");
        }
    }

    private PlanRow requirePlan(UUID vehicle, UUID item) {
        PlanRow p = repo.plan(vehicle, item);
        if (p == null) fail(HttpStatus.NOT_FOUND, "MAINTENANCE_PLAN_NOT_FOUND");
        return p;
    }

    private EventRow requireEvent(UUID vehicle, UUID event) {
        EventRow e = repo.event(vehicle, event);
        if (e == null) fail(HttpStatus.NOT_FOUND, "MAINTENANCE_EVENT_NOT_FOUND");
        return e;
    }

    private boolean verifiedEvidence(PlanRow p) {
        var e = repo.evidence(p.vehicle(), p.evidence(), p.name(), p.km(), p.months());
        return e != null && e.verified();
    }

    private Plan view(PlanRow p) {
        return new Plan(
                p.id(),
                p.name(),
                p.shared(),
                p.active(),
                p.basis(),
                repo.evidence(p.vehicle(), p.evidence(), p.name(), p.km(), p.months()),
                p.km(),
                p.months(),
                p.revision(),
                p.lastDate(),
                p.lastKm(),
                p.dueDate(),
                p.dueKm());
    }

    private EventInput parse(EventRow row) {
        return json.readValue(row.payload(), EventInput.class);
    }

    private Event eventView(EventRow row, UUID viewer) {
        EventInput b = parse(row);
        return eventView(
                row.id(), row.revision(), b, row.author().equals(viewer) || b.detailsShared());
    }

    private static Event eventView(UUID id, long revision, EventInput b, boolean details) {
        return new Event(
                id,
                b.itemId(),
                b.serviceDate(),
                b.odometerKm(),
                b.status(),
                b.shared(),
                b.detailsShared(),
                revision,
                details
                        ? new PrivateDetails(
                                b.cost(), b.currency(), b.memo(), b.shop(), b.receiptUrl())
                        : null);
    }

    private void validateEvent(EventInput b) {
        validateBean(b);
        if (b.serviceDate() == null && b.odometerKm() == null)
            fail(HttpStatus.BAD_REQUEST, "SERVICE_DATE_OR_ODOMETER_REQUIRED");
        if (b.serviceDate() != null
                && (b.serviceDate().getYear() < 1900
                        || b.serviceDate().isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))))
            fail(HttpStatus.BAD_REQUEST, "INVALID_SERVICE_DATE");
        if (!b.shared() && b.detailsShared())
            fail(HttpStatus.BAD_REQUEST, "PRIVATE_EVENT_DETAILS_NOT_SHAREABLE");
        if (b.cost() != null && b.currency() == null)
            fail(HttpStatus.BAD_REQUEST, "CURRENCY_REQUIRED");
        if (b.currency() != null) {
            try {
                Currency.getInstance(b.currency());
            } catch (IllegalArgumentException ex) {
                fail(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY");
            }
        }
        if (b.receiptUrl() != null) {
            try {
                URI uri = URI.create(b.receiptUrl());
                if (!"https".equals(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null)
                    fail(HttpStatus.BAD_REQUEST, "INVALID_RECEIPT_URL");
            } catch (IllegalArgumentException ex) {
                fail(HttpStatus.BAD_REQUEST, "INVALID_RECEIPT_URL");
            }
        }
    }

    private void validateBean(Object b) {
        if (!validator.validate(b).isEmpty()) fail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private byte[] hash(Object b) {
        return InstallationAuth.sha256(json.writeValueAsString(b).getBytes(StandardCharsets.UTF_8));
    }
    // 1과 1.0을 같은 요청으로 비교하도록 저장·해시 전에 숫자를 정규화한다.
    private static EventInput canonical(EventInput b) {
        return new EventInput(
                b.itemId(),
                b.serviceDate(),
                number(b.odometerKm()),
                b.status(),
                b.shared(),
                b.detailsShared(),
                number(b.cost()),
                b.currency(),
                b.memo(),
                b.shop(),
                b.receiptUrl());
    }

    private static BigDecimal number(BigDecimal n) {
        return n == null ? null : n.stripTrailingZeros();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static long expected(String value) {
        if (value == null) fail(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED");
        if (!value.matches("[0-9]+|\"[0-9]+\"")) fail(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH");
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException ex) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH");
            return 0;
        }
    }

    private static void match(String ifMatch, long revision) {
        if (expected(ifMatch) != revision)
            fail(HttpStatus.PRECONDITION_FAILED, "REVISION_MISMATCH");
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }
}
