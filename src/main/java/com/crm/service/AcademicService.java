package com.crm.service;

import com.crm.dto.request.*;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.entity.enums.GroupStatus;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AcademicService {

    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final SubjectRepository subjectRepository;
    private final ClassroomRepository classroomRepository;
    private final TimetableRepository timetableRepository;
    private final TeacherRepository teacherRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final TeacherAccessService teacherAccessService;
    private final Clock billingClock;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] GRID_COLORS = {
        "#4f46e5", "#0891b2", "#16a34a", "#ca8a04", "#dc2626",
        "#9333ea", "#db2777", "#ea580c", "#0d9488", "#2563eb"
    };

    // ── Classes ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<ClassResponse> getAllClasses(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<ClassEntity> p = classRepository.findByIsActiveTrue(pageable);
        return PageResponse.<ClassResponse>builder()
            .content(p.getContent().stream().map(this::toClassResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ClassResponse getClassById(Long id) {
        return toClassResponse(findClassById(id));
    }

    @Transactional
    public ClassResponse createClass(ClassRequest request) {
        ClassEntity c = ClassEntity.builder()
            .className(request.getClassName())
            .classCode(request.getClassCode())
            .isActive(true)
            .build();
        return toClassResponse(classRepository.save(c));
    }

    @Transactional
    public ClassResponse updateClass(Long id, ClassRequest request) {
        ClassEntity c = findClassById(id);
        c.setClassName(request.getClassName());
        c.setClassCode(request.getClassCode());
        return toClassResponse(classRepository.save(c));
    }

    @Transactional
    public void deleteClass(Long id) {
        ClassEntity c = findClassById(id);
        c.setIsActive(false);
        classRepository.save(c);
    }

    // ── Sections ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<SectionResponse> getAllSections(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Section> p = sectionRepository.findByIsActiveTrue(pageable);
        return PageResponse.<SectionResponse>builder()
            .content(p.getContent().stream().map(this::toSectionResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public SectionResponse getSectionById(Long id) {
        return toSectionResponse(findSectionById(id));
    }

    @Transactional
    public SectionResponse createSection(SectionRequest request) {
        Section s = Section.builder()
            .sectionName(request.getSectionName())
            .room(request.getRoom())
            .maxStudents(request.getMaxStudents() != null ? request.getMaxStudents() : 30)
            .isActive(true)
            .build();
        if (request.getClassId() != null) s.setClassEntity(findClassById(request.getClassId()));
        if (request.getTeacherId() != null) s.setTeacher(findTeacherById(request.getTeacherId()));
        return toSectionResponse(sectionRepository.save(s));
    }

    @Transactional
    public SectionResponse updateSection(Long id, SectionRequest request) {
        Section s = findSectionById(id);
        s.setSectionName(request.getSectionName());
        s.setRoom(request.getRoom());
        if (request.getMaxStudents() != null) s.setMaxStudents(request.getMaxStudents());
        if (request.getClassId() != null) s.setClassEntity(findClassById(request.getClassId()));
        if (request.getTeacherId() != null) s.setTeacher(findTeacherById(request.getTeacherId()));
        return toSectionResponse(sectionRepository.save(s));
    }

    @Transactional
    public void deleteSection(Long id) {
        Section s = findSectionById(id);
        s.setIsActive(false);
        sectionRepository.save(s);
    }

    // ── Subjects ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<SubjectResponse> getAllSubjects(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Subject> p = subjectRepository.findByIsActiveTrue(pageable);
        return PageResponse.<SubjectResponse>builder()
            .content(p.getContent().stream().map(this::toSubjectResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public SubjectResponse getSubjectById(Long id) {
        return toSubjectResponse(findSubjectById(id));
    }

    @Transactional
    public SubjectResponse createSubject(SubjectRequest request) {
        Subject s = Subject.builder()
            .subjectName(request.getSubjectName())
            .subjectCode(request.getSubjectCode())
            .isActive(true)
            .build();
        if (request.getClassId() != null) s.setClassEntity(findClassById(request.getClassId()));
        if (request.getTeacherId() != null) s.setTeacher(findTeacherById(request.getTeacherId()));
        return toSubjectResponse(subjectRepository.save(s));
    }

    @Transactional
    public SubjectResponse updateSubject(Long id, SubjectRequest request) {
        Subject s = findSubjectById(id);
        s.setSubjectName(request.getSubjectName());
        s.setSubjectCode(request.getSubjectCode());
        if (request.getClassId() != null) s.setClassEntity(findClassById(request.getClassId()));
        if (request.getTeacherId() != null) s.setTeacher(findTeacherById(request.getTeacherId()));
        return toSubjectResponse(subjectRepository.save(s));
    }

    @Transactional
    public void deleteSubject(Long id) {
        Subject s = findSubjectById(id);
        s.setIsActive(false);
        subjectRepository.save(s);
    }

    // ── Timetable ──────────────────────────────────────────────────

    /**
     * {@code includeClosed = false} (standart): faqat ACTIVE/FORMING guruhlar va {@code end_date}
     * bugundan oldin bo'lmaganlar. {@code true} — hammasi (faqat SUPER_ADMIN/ADMIN).
     */
    @Transactional(readOnly = true)
    public PageResponse<TimetableResponse> getAllTimetable(int page, int size, boolean includeClosed) {
        LocalDate onDate = visibilityDate(includeClosed, null);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Timetable> p;
        var teacherScope = teacherAccessService.resolveTeacherScope();
        if (teacherScope.isPresent()) {
            p = onDate == null
                ? timetableRepository.findByTeacherScope(teacherScope.get().getId(), pageable)
                : timetableRepository.findOpenByTeacherScope(
                    teacherScope.get().getId(), OPEN_GROUP_STATUSES, onDate, pageable);
        } else {
            p = onDate == null
                ? timetableRepository.findAll(pageable)
                : timetableRepository.findOpen(OPEN_GROUP_STATUSES, onDate, pageable);
        }
        return PageResponse.<TimetableResponse>builder()
            .content(p.getContent().stream().map(this::toTimetableResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public TimetableResponse getTimetableById(Long id) {
        Timetable t = findTimetableById(id);
        assertTimetableAccess(t);
        return toTimetableResponse(t);
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetableByClass(Long classId, boolean includeClosed) {
        LocalDate onDate = visibilityDate(includeClosed, null);
        return timetableRepository.findByClassEntityId(classId).stream()
            .filter(this::isTimetableVisible)
            .filter(t -> isOpenOn(t, onDate))
            .map(this::toTimetableResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetableByGroup(Long groupId, boolean includeClosed) {
        teacherAccessService.assertOwnsGroup(groupId);
        LocalDate onDate = visibilityDate(includeClosed, null);
        return timetableRepository.findByGroupId(groupId).stream()
            .filter(t -> isOpenOn(t, onDate))
            .map(this::toTimetableResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetableByTeacher(Long teacherId, boolean includeClosed) {
        LocalDate onDate = visibilityDate(includeClosed, null);
        var teacherScope = teacherAccessService.resolveTeacherScope();
        if (teacherScope.isPresent() && !teacherScope.get().getId().equals(teacherId)) {
            throw new com.crm.exception.ForbiddenException("Faqat o'z jadvalingizni ko'ra olasiz");
        }
        return timetableRepository.findByTeacherId(teacherId).stream()
            .filter(t -> isOpenOn(t, onDate))
            .map(this::toTimetableResponse).collect(Collectors.toList());
    }

    /** {@link #getTimetableGrid(String, LocalDate, boolean)} bilan bir xil filtr. */
    @Transactional(readOnly = true)
    public RoomTimetableDto getByRoom(String dayOfWeek, LocalDate date, boolean includeClosed) {
        String day = resolveDay(dayOfWeek, date);
        LocalDate onDate = visibilityDate(includeClosed, date);
        List<Classroom> rooms = classroomRepository.findAllByOrderByRoomNumberAsc();
        List<Timetable> entries = loadDayEntries(day, onDate);

        List<RoomLessonDto> lessons = entries.stream().map(t -> {
            RoomLessonDto dto = new RoomLessonDto();
            dto.setId(t.getId());
            dto.setClassroomId(t.getClassroom() != null ? t.getClassroom().getId() : null);
            Group g = t.getGroup();
            long studentCount = 0;
            if (g != null) {
                dto.setGroupId(g.getId());
                dto.setGroupName(g.getGroupName());
                Long teacherId = resolveTimetableTeacherId(t);
                String teacherName = resolveTimetableTeacherName(t);
                dto.setTeacherId(teacherId);
                dto.setTeacherName(teacherName);
                studentCount = studentGroupRepository.countByGroupIdAndIsActiveTrue(g.getId());
                dto.setStudentCount(studentCount);
            }
            dto.setStartTime(t.getStartTime().format(TIME_FMT));
            dto.setEndTime(t.getEndTime().format(TIME_FMT));
            if (t.getClassroom() != null) {
                int capacity = t.getClassroom().getCapacity() != null ? t.getClassroom().getCapacity() : 0;
                dto.setCapacity(capacity);
                dto.setFreeSeats(Math.max(0, capacity - (int) studentCount));
            }
            return dto;
        }).collect(Collectors.toList());

        RoomTimetableDto result = new RoomTimetableDto();
        result.setClassrooms(rooms.stream()
            .map(r -> new ClassroomBriefDto(r.getId(), classroomDisplayName(r), r.getCapacity()))
            .collect(Collectors.toList()));
        result.setLessons(lessons);
        return result;
    }

    /**
     * Kunlik jadval. {@code date} berilsa hafta kuni undan olinadi ({@code dayOfWeek} ixtiyoriy,
     * berilsa mos kelishi shart) va {@code end_date} si shu sanadan oldin bo'lgan guruh darslari
     * chiqmaydi; berilmasa — bugungi sana bo'yicha. Standart holda faqat ACTIVE/FORMING guruhlar;
     * {@code includeClosed = true} — hammasi (faqat SUPER_ADMIN/ADMIN).
     */
    @Transactional(readOnly = true)
    public TimetableGridResponse getTimetableGrid(String dayOfWeek, LocalDate date, boolean includeClosed) {
        String day = resolveDay(dayOfWeek, date);
        LocalDate onDate = visibilityDate(includeClosed, date);
        List<Classroom> rooms = classroomRepository.findByIsActiveTrueOrderByRoomNumberAsc();
        List<Timetable> entries = loadDayEntries(day, onDate);

        List<TimetableGridResponse.TimetableGridRoomDto> roomDtos = rooms.stream()
            .map(r -> TimetableGridResponse.TimetableGridRoomDto.builder()
                .id(r.getId())
                .name(classroomDisplayName(r))
                .capacity(r.getCapacity())
                .build())
            .collect(Collectors.toList());

        List<TimetableGridResponse.TimetableGridEntryDto> entryDtos = entries.stream()
            .map(t -> {
                Group g = t.getGroup();
                Long groupId = g != null ? g.getId() : null;
                String courseName = resolveTimetableSubjectName(t);
                return TimetableGridResponse.TimetableGridEntryDto.builder()
                    .id(t.getId())
                    .roomId(t.getClassroom() != null ? t.getClassroom().getId() : null)
                    .groupId(groupId)
                    .groupName(g != null ? g.getGroupName() : null)
                    .courseName(courseName)
                    .teacherName(resolveTimetableTeacherName(t))
                    .startTime(t.getStartTime() != null ? t.getStartTime().format(TIME_FMT) : null)
                    .endTime(t.getEndTime() != null ? t.getEndTime().format(TIME_FMT) : null)
                    .color(gridColor(groupId))
                    .build();
            })
            .collect(Collectors.toList());

        return TimetableGridResponse.builder()
            .dayOfWeek(day)
            .startTime("08:00")
            .endTime("22:00")
            .slotMinutes(30)
            .rooms(roomDtos)
            .entries(entryDtos)
            .build();
    }

    @Transactional
    public TimetableResponse createTimetable(TimetableRequest request) {
        String day = resolveDays(request).get(0);
        TimetableRequest single = copyWithDay(request, day);
        Timetable t = buildTimetable(new Timetable(), single);
        assertNoConflicts(t, null);
        return toTimetableResponse(timetableRepository.save(t));
    }

    @Transactional
    public List<TimetableResponse> createTimetableBatch(TimetableRequest request) {
        List<String> days = resolveDays(request);
        List<Timetable> toSave = new ArrayList<>();
        for (String day : days) {
            TimetableRequest dayReq = copyWithDay(request, day);
            Timetable t = buildTimetable(new Timetable(), dayReq);
            assertNoConflicts(t, null);
            // Also check against other days in this batch (same room/teacher)
            for (Timetable pending : toSave) {
                assertNoConflictWith(t, pending);
            }
            toSave.add(t);
        }
        return toSave.stream()
            .map(timetableRepository::save)
            .map(this::toTimetableResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public TimetableResponse updateTimetable(Long id, TimetableRequest request) {
        Timetable t = findTimetableById(id);
        buildTimetable(t, request);
        assertNoConflicts(t, id);
        return toTimetableResponse(timetableRepository.save(t));
    }

    @Transactional
    public void deleteTimetable(Long id) {
        timetableRepository.delete(findTimetableById(id));
    }

    // ── Finders ────────────────────────────────────────────────────

    public ClassEntity findClassById(Long id) {
        return classRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Class", id));
    }

    Section findSectionById(Long id) {
        return sectionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Section", id));
    }

    Subject findSubjectById(Long id) {
        return subjectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Subject", id));
    }

    private Classroom findClassroomById(Long id) {
        return classroomRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Classroom", id));
    }

    private static String classroomDisplayName(Classroom c) {
        if (c.getRoomNumber() != null && !c.getRoomNumber().isBlank()) {
            return c.getRoomNumber();
        }
        return c.getRoomName();
    }

    private Timetable findTimetableById(Long id) {
        return timetableRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Timetable", id));
    }

    private List<Timetable> loadDayEntries(String day, LocalDate onDate) {
        var teacherScope = teacherAccessService.resolveTeacherScope();
        List<Timetable> entries = teacherScope.isPresent()
            ? timetableRepository.findByDayOfWeekWithDetailsForTeacher(day, teacherScope.get().getId())
            : timetableRepository.findByDayOfWeekWithDetails(day);
        return entries.stream().filter(t -> isOpenOn(t, onDate)).collect(Collectors.toList());
    }

    // ── Jadval ko'rinishi: yopilgan guruhlar ────────────────────────────

    /**
     * Jadvalda standart holda ko'rinadigan guruh holatlari. Guruh COMPLETED/CANCELLED bo'lganda
     * uning timetable qatorlari o'chirilmaydi ({@code GroupService.updateStatus} ularga tegmaydi) —
     * shuning uchun filtr o'qishda qo'llanadi.
     */
    static final Set<GroupStatus> OPEN_GROUP_STATUSES = EnumSet.of(GroupStatus.ACTIVE, GroupStatus.FORMING);

    /**
     * Filtr sanasi: {@code null} — filtr yo'q ({@code includeClosed}); aks holda {@code date}
     * yoki bugun. {@code includeClosed} faqat SUPER_ADMIN/ADMIN ga ruxsat (aks holda 403).
     */
    private LocalDate visibilityDate(boolean includeClosed, LocalDate date) {
        if (includeClosed) {
            if (!teacherAccessService.isCurrentUserAdmin()) {
                throw new com.crm.exception.ForbiddenException(
                    "includeClosed faqat SUPER_ADMIN va ADMIN uchun");
            }
            return null;
        }
        return date != null ? date : LocalDate.now(billingClock);
    }

    /**
     * Dars {@code onDate} kuni jadvalda ko'rinadimi: guruh ACTIVE/FORMING va {@code end_date}
     * {@code onDate} dan oldin emas. Guruhsiz (eski sinf) yozuv va {@code onDate = null} — doim.
     */
    static boolean isOpenOn(Timetable t, LocalDate onDate) {
        if (onDate == null || t.getGroup() == null) {
            return true;
        }
        Group g = t.getGroup();
        if (g.getStatus() != null && !OPEN_GROUP_STATUSES.contains(g.getStatus())) {
            return false;
        }
        return g.getEndDate() == null || !g.getEndDate().isBefore(onDate);
    }

    /** {@code date} berilsa hafta kuni undan; {@code dayOfWeek} ham berilsa mos kelishi shart. */
    private static String resolveDay(String dayOfWeek, LocalDate date) {
        if (date == null) {
            return normalizeDayOfWeek(dayOfWeek);
        }
        String fromDate = date.getDayOfWeek().name();
        if (dayOfWeek != null && !dayOfWeek.isBlank() && !normalizeDayOfWeek(dayOfWeek).equals(fromDate)) {
            throw new BadRequestException("dayOfWeek (" + dayOfWeek + ") sana bilan mos emas: "
                + date + " — " + fromDate);
        }
        return fromDate;
    }

    private void assertTimetableAccess(Timetable t) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return;
        }
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        Long ownerId = resolveTimetableTeacherId(t);
        if (ownerId == null || !ownerId.equals(teacher.getId())) {
            throw new com.crm.exception.ForbiddenException("Bu dars sizga tegishli emas");
        }
    }

    private boolean isTimetableVisible(Timetable t) {
        if (!teacherAccessService.isCurrentUserTeacher()) {
            return true;
        }
        Teacher teacher = teacherAccessService.getCurrentTeacherOrThrow();
        Long ownerId = resolveTimetableTeacherId(t);
        return ownerId != null && ownerId.equals(teacher.getId());
    }

    private Group findGroupById(Long id) {
        return groupRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Group", id));
    }

    private Teacher findTeacherById(Long id) {
        return teacherRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Teacher", id));
    }

    // ── Mappers ────────────────────────────────────────────────────

    private ClassResponse toClassResponse(ClassEntity c) {
        return ClassResponse.builder()
            .id(c.getId()).className(c.getClassName())
            .classCode(c.getClassCode()).isActive(c.getIsActive())
            .createdAt(c.getCreatedAt()).build();
    }

    private SectionResponse toSectionResponse(Section s) {
        return SectionResponse.builder()
            .id(s.getId()).sectionName(s.getSectionName())
            .classId(s.getClassEntity() != null ? s.getClassEntity().getId() : null)
            .className(s.getClassEntity() != null ? s.getClassEntity().getClassName() : null)
            .teacherId(s.getTeacher() != null ? s.getTeacher().getId() : null)
            .teacherName(s.getTeacher() != null
                ? s.getTeacher().getFirstName() + " " + s.getTeacher().getLastName() : null)
            .room(s.getRoom()).maxStudents(s.getMaxStudents())
            .isActive(s.getIsActive()).createdAt(s.getCreatedAt()).build();
    }

    private SubjectResponse toSubjectResponse(Subject s) {
        return SubjectResponse.builder()
            .id(s.getId()).subjectName(s.getSubjectName()).subjectCode(s.getSubjectCode())
            .classId(s.getClassEntity() != null ? s.getClassEntity().getId() : null)
            .className(s.getClassEntity() != null ? s.getClassEntity().getClassName() : null)
            .teacherId(s.getTeacher() != null ? s.getTeacher().getId() : null)
            .teacherName(s.getTeacher() != null
                ? s.getTeacher().getFirstName() + " " + s.getTeacher().getLastName() : null)
            .isActive(s.getIsActive()).createdAt(s.getCreatedAt()).build();
    }

    private TimetableResponse toTimetableResponse(Timetable t) {
        return TimetableResponse.builder()
            .id(t.getId())
            .groupId(t.getGroup() != null ? t.getGroup().getId() : null)
            .groupName(t.getGroup() != null ? t.getGroup().getGroupName() : null)
            .classId(t.getClassEntity() != null ? t.getClassEntity().getId() : null)
            .className(t.getClassEntity() != null ? t.getClassEntity().getClassName() : null)
            .sectionId(t.getSection() != null ? t.getSection().getId() : null)
            .sectionName(t.getSection() != null ? t.getSection().getSectionName() : null)
            .subjectId(t.getSubject() != null ? t.getSubject().getId() : null)
            .subjectName(resolveTimetableSubjectName(t))
            .teacherId(resolveTimetableTeacherId(t))
            .teacherName(resolveTimetableTeacherName(t))
            .classroomId(t.getClassroom() != null ? t.getClassroom().getId() : null)
            .roomName(t.getClassroom() != null ? classroomDisplayName(t.getClassroom()) : null)
            .roomNumber(t.getClassroom() != null ? t.getClassroom().getRoomNumber() : null)
            .dayOfWeek(t.getDayOfWeek()).startTime(t.getStartTime()).endTime(t.getEndTime())
            .academicYear(t.getAcademicYear()).createdAt(t.getCreatedAt()).build();
    }

    private static String resolveTimetableSubjectName(Timetable t) {
        if (t.getSubject() != null) {
            return t.getSubject().getSubjectName();
        }
        if (t.getSubjectName() != null && !t.getSubjectName().isBlank()) {
            return t.getSubjectName();
        }
        if (t.getGroup() != null && t.getGroup().getCourse() != null) {
            return t.getGroup().getCourse().getCourseName();
        }
        return null;
    }

    private static Long resolveTimetableTeacherId(Timetable t) {
        if (t.getTeacher() != null) {
            return t.getTeacher().getId();
        }
        if (t.getGroup() != null && t.getGroup().getTeacher() != null) {
            return t.getGroup().getTeacher().getId();
        }
        return null;
    }

    private static String resolveTimetableTeacherName(Timetable t) {
        if (t.getTeacher() != null) {
            return t.getTeacher().getFirstName() + " " + t.getTeacher().getLastName();
        }
        if (t.getGroup() != null && t.getGroup().getTeacher() != null) {
            Teacher gt = t.getGroup().getTeacher();
            return gt.getFirstName() + " " + gt.getLastName();
        }
        return null;
    }

    private Timetable buildTimetable(Timetable t, TimetableRequest req) {
        t.setDayOfWeek(normalizeDayOfWeek(req.getDayOfWeek()));
        t.setStartTime(req.getStartTime());
        t.setEndTime(req.getEndTime());
        t.setAcademicYear(req.getAcademicYear());
        if (req.getGroupId() != null) t.setGroup(findGroupById(req.getGroupId()));
        if (req.getClassId() != null) t.setClassEntity(findClassById(req.getClassId()));
        if (req.getSectionId() != null) t.setSection(findSectionById(req.getSectionId()));
        if (req.getSubjectId() != null) t.setSubject(findSubjectById(req.getSubjectId()));
        if (req.getTeacherId() != null) t.setTeacher(findTeacherById(req.getTeacherId()));
        if (req.getClassroomId() != null) t.setClassroom(findClassroomById(req.getClassroomId()));
        else if (req.getRoomNumber() != null && !req.getRoomNumber().isBlank()) {
            classroomRepository.findFirstByRoomNumberIgnoreCase(req.getRoomNumber().trim())
                .ifPresent(t::setClassroom);
        }
        return t;
    }

    /**
     * Xona va o'qituvchi to'qnashuvi. Yopiq (COMPLETED/CANCELLED) va {@code end_date} i bugundan oldin tugagan guruh
     * darslari hisobga OLINMAYDI — dars jadvali bilan bir xil qoida ({@link #isOpenOn}): ular jadvalda ko'rinmaydi,
     * xona va o'qituvchini band qilmaydi.
     */
    private void assertNoConflicts(Timetable candidate, Long excludeId) {
        if (candidate.getStartTime() == null || candidate.getEndTime() == null
                || candidate.getDayOfWeek() == null) {
            return;
        }
        LocalDate today = LocalDate.now(billingClock);
        if (!candidate.getStartTime().isBefore(candidate.getEndTime())) {
            throw new BadRequestException("Boshlanish vaqti tugashdan oldin bo'lishi kerak");
        }

        if (candidate.getClassroom() != null) {
            List<Timetable> roomEntries = timetableRepository.findByClassroom_IdAndDayOfWeek(
                candidate.getClassroom().getId(), candidate.getDayOfWeek());
            for (Timetable existing : roomEntries) {
                if ((excludeId != null && excludeId.equals(existing.getId())) || !isOpenOn(existing, today)) {
                    continue;
                }
                if (timesOverlap(candidate.getStartTime(), candidate.getEndTime(),
                        existing.getStartTime(), existing.getEndTime())) {
                    String groupName = existing.getGroup() != null
                        ? existing.getGroup().getGroupName() : "noma'lum guruh";
                    String roomLabel = classroomDisplayName(candidate.getClassroom());
                    throw new BadRequestException(String.format(
                        "%s %s—%s da %s band (%s)",
                        dayToUzbek(candidate.getDayOfWeek()),
                        candidate.getStartTime().format(TIME_FMT),
                        candidate.getEndTime().format(TIME_FMT),
                        roomLabel,
                        groupName));
                }
            }
        }

        Long teacherId = resolveTimetableTeacherId(candidate);
        if (teacherId != null) {
            List<Timetable> dayEntries = timetableRepository.findByDayOfWeek(candidate.getDayOfWeek());
            for (Timetable existing : dayEntries) {
                if ((excludeId != null && excludeId.equals(existing.getId())) || !isOpenOn(existing, today)) {
                    continue;
                }
                Long existingTeacherId = resolveTimetableTeacherId(existing);
                if (!teacherId.equals(existingTeacherId)) {
                    continue;
                }
                if (timesOverlap(candidate.getStartTime(), candidate.getEndTime(),
                        existing.getStartTime(), existing.getEndTime())) {
                    throw new BadRequestException(String.format(
                        "%s %s—%s da o'qituvchi band",
                        dayToUzbek(candidate.getDayOfWeek()),
                        candidate.getStartTime().format(TIME_FMT),
                        candidate.getEndTime().format(TIME_FMT)));
                }
            }
        }
    }

    private void assertNoConflictWith(Timetable candidate, Timetable other) {
        if (!candidate.getDayOfWeek().equals(other.getDayOfWeek())) {
            return;
        }
        if (!timesOverlap(candidate.getStartTime(), candidate.getEndTime(),
                other.getStartTime(), other.getEndTime())) {
            return;
        }
        if (candidate.getClassroom() != null && other.getClassroom() != null
                && candidate.getClassroom().getId().equals(other.getClassroom().getId())) {
            throw new BadRequestException(String.format(
                "%s %s—%s da %s band (shu so'rov ichida)",
                dayToUzbek(candidate.getDayOfWeek()),
                candidate.getStartTime().format(TIME_FMT),
                candidate.getEndTime().format(TIME_FMT),
                classroomDisplayName(candidate.getClassroom())));
        }
        Long a = resolveTimetableTeacherId(candidate);
        Long b = resolveTimetableTeacherId(other);
        if (a != null && a.equals(b)) {
            throw new BadRequestException(String.format(
                "%s %s—%s da o'qituvchi band (shu so'rov ichida)",
                dayToUzbek(candidate.getDayOfWeek()),
                candidate.getStartTime().format(TIME_FMT),
                candidate.getEndTime().format(TIME_FMT)));
        }
    }

    private static List<String> resolveDays(TimetableRequest request) {
        LinkedHashSet<String> days = new LinkedHashSet<>();
        if (request.getDaysOfWeek() != null) {
            for (String d : request.getDaysOfWeek()) {
                if (d != null && !d.isBlank()) {
                    days.add(normalizeDayOfWeek(d));
                }
            }
        }
        if (days.isEmpty() && request.getDayOfWeek() != null && !request.getDayOfWeek().isBlank()) {
            days.add(normalizeDayOfWeek(request.getDayOfWeek()));
        }
        if (days.isEmpty()) {
            throw new BadRequestException("dayOfWeek yoki daysOfWeek majburiy");
        }
        return new ArrayList<>(days);
    }

    private static TimetableRequest copyWithDay(TimetableRequest src, String day) {
        TimetableRequest copy = new TimetableRequest();
        copy.setGroupId(src.getGroupId());
        copy.setClassId(src.getClassId());
        copy.setSectionId(src.getSectionId());
        copy.setSubjectId(src.getSubjectId());
        copy.setTeacherId(src.getTeacherId());
        copy.setClassroomId(src.getClassroomId());
        copy.setDayOfWeek(day);
        copy.setStartTime(src.getStartTime());
        copy.setEndTime(src.getEndTime());
        copy.setAcademicYear(src.getAcademicYear());
        copy.setRoomNumber(src.getRoomNumber());
        return copy;
    }

    private static String dayToUzbek(String day) {
        if (day == null) {
            return "";
        }
        return switch (day.toUpperCase(Locale.ROOT)) {
            case "MONDAY" -> "Dushanba";
            case "TUESDAY" -> "Seshanba";
            case "WEDNESDAY" -> "Chorshanba";
            case "THURSDAY" -> "Payshanba";
            case "FRIDAY" -> "Juma";
            case "SATURDAY" -> "Shanba";
            case "SUNDAY" -> "Yakshanba";
            default -> day;
        };
    }

    private static boolean timesOverlap(LocalTime newStart, LocalTime newEnd,
                                        LocalTime existingStart, LocalTime existingEnd) {
        if (newStart == null || newEnd == null || existingStart == null || existingEnd == null) {
            return false;
        }
        return newStart.isBefore(existingEnd) && newEnd.isAfter(existingStart);
    }

    private static String normalizeDayOfWeek(String dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek.isBlank()) {
            throw new BadRequestException("dayOfWeek majburiy");
        }
        try {
            return DayOfWeek.valueOf(dayOfWeek.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Noto'g'ri dayOfWeek: " + dayOfWeek);
        }
    }

    private static String gridColor(Long groupId) {
        if (groupId == null) {
            return GRID_COLORS[0];
        }
        int idx = (int) (Math.floorMod(groupId, (long) GRID_COLORS.length));
        return GRID_COLORS[idx];
    }
}
