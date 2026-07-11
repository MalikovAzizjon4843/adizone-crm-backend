package com.crm.service;

import com.crm.dto.request.*;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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

    @Transactional(readOnly = true)
    public PageResponse<TimetableResponse> getAllTimetable(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Timetable> p = timetableRepository.findAll(pageable);
        return PageResponse.<TimetableResponse>builder()
            .content(p.getContent().stream().map(this::toTimetableResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public TimetableResponse getTimetableById(Long id) {
        return toTimetableResponse(findTimetableById(id));
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetableByClass(Long classId) {
        return timetableRepository.findByClassEntityId(classId).stream()
            .map(this::toTimetableResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetableByGroup(Long groupId) {
        return timetableRepository.findByGroupId(groupId).stream()
            .map(this::toTimetableResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetableByTeacher(Long teacherId) {
        return timetableRepository.findByTeacherId(teacherId).stream()
            .map(this::toTimetableResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RoomTimetableDto getByRoom(String dayOfWeek) {
        String day = normalizeDayOfWeek(dayOfWeek);
        List<Classroom> rooms = classroomRepository.findAllByOrderByRoomNumberAsc();
        List<Timetable> entries = timetableRepository.findByDayOfWeek(day);

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

    @Transactional(readOnly = true)
    public TimetableGridResponse getTimetableGrid(String dayOfWeek) {
        String day = normalizeDayOfWeek(dayOfWeek);
        List<Classroom> rooms = classroomRepository.findByIsActiveTrueOrderByRoomNumberAsc();
        // LEFT JOIN FETCH — classroom NULL entries are included (Xonasiz)
        List<Timetable> entries = timetableRepository.findByDayOfWeekWithDetails(day);

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
        Timetable t = buildTimetable(new Timetable(), request);
        assertNoConflicts(t, null);
        return toTimetableResponse(timetableRepository.save(t));
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

    private void assertNoConflicts(Timetable candidate, Long excludeId) {
        if (candidate.getStartTime() == null || candidate.getEndTime() == null
                || candidate.getDayOfWeek() == null) {
            return;
        }
        if (!candidate.getStartTime().isBefore(candidate.getEndTime())) {
            throw new BadRequestException("Boshlanish vaqti tugashdan oldin bo'lishi kerak");
        }

        if (candidate.getClassroom() != null) {
            List<Timetable> roomEntries = timetableRepository.findByClassroom_IdAndDayOfWeek(
                candidate.getClassroom().getId(), candidate.getDayOfWeek());
            for (Timetable existing : roomEntries) {
                if (excludeId != null && excludeId.equals(existing.getId())) {
                    continue;
                }
                if (timesOverlap(candidate.getStartTime(), candidate.getEndTime(),
                        existing.getStartTime(), existing.getEndTime())) {
                    String groupName = existing.getGroup() != null
                        ? existing.getGroup().getGroupName() : "noma'lum guruh";
                    throw new BadRequestException(String.format(
                        "Bu xona %s—%s oralig'ida band (%s)",
                        existing.getStartTime().format(TIME_FMT),
                        existing.getEndTime().format(TIME_FMT),
                        groupName));
                }
            }
        }

        Long teacherId = resolveTimetableTeacherId(candidate);
        if (teacherId != null) {
            List<Timetable> dayEntries = timetableRepository.findByDayOfWeek(candidate.getDayOfWeek());
            for (Timetable existing : dayEntries) {
                if (excludeId != null && excludeId.equals(existing.getId())) {
                    continue;
                }
                Long existingTeacherId = resolveTimetableTeacherId(existing);
                if (!teacherId.equals(existingTeacherId)) {
                    continue;
                }
                if (timesOverlap(candidate.getStartTime(), candidate.getEndTime(),
                        existing.getStartTime(), existing.getEndTime())) {
                    throw new BadRequestException("O'qituvchi bu vaqtda band");
                }
            }
        }
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
