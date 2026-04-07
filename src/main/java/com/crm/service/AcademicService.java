package com.crm.service;

import com.crm.dto.request.*;
import com.crm.dto.response.*;
import com.crm.entity.*;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    // ── Classrooms ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<ClassroomResponse> getAllClassrooms(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Classroom> p = classroomRepository.findByIsActiveTrue(pageable);
        return PageResponse.<ClassroomResponse>builder()
            .content(p.getContent().stream().map(this::toClassroomResponse).collect(Collectors.toList()))
            .pageNumber(page).pageSize(size)
            .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).last(p.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ClassroomResponse getClassroomById(Long id) {
        return toClassroomResponse(findClassroomById(id));
    }

    @Transactional
    public ClassroomResponse createClassroom(ClassroomRequest request) {
        Classroom c = Classroom.builder()
            .roomName(request.getRoomName())
            .roomNumber(request.getRoomNumber())
            .capacity(request.getCapacity())
            .floor(request.getFloor())
            .isActive(true)
            .build();
        return toClassroomResponse(classroomRepository.save(c));
    }

    @Transactional
    public ClassroomResponse updateClassroom(Long id, ClassroomRequest request) {
        Classroom c = findClassroomById(id);
        c.setRoomName(request.getRoomName());
        c.setRoomNumber(request.getRoomNumber());
        c.setCapacity(request.getCapacity());
        c.setFloor(request.getFloor());
        return toClassroomResponse(classroomRepository.save(c));
    }

    @Transactional
    public void deleteClassroom(Long id) {
        Classroom c = findClassroomById(id);
        c.setIsActive(false);
        classroomRepository.save(c);
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

    @Transactional
    public TimetableResponse createTimetable(TimetableRequest request) {
        Timetable t = buildTimetable(new Timetable(), request);
        return toTimetableResponse(timetableRepository.save(t));
    }

    @Transactional
    public TimetableResponse updateTimetable(Long id, TimetableRequest request) {
        Timetable t = findTimetableById(id);
        buildTimetable(t, request);
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

    private ClassroomResponse toClassroomResponse(Classroom c) {
        return ClassroomResponse.builder()
            .id(c.getId()).roomName(c.getRoomName()).roomNumber(c.getRoomNumber())
            .capacity(c.getCapacity()).floor(c.getFloor())
            .isActive(c.getIsActive()).createdAt(c.getCreatedAt()).build();
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
            .subjectName(t.getSubject() != null ? t.getSubject().getSubjectName() : null)
            .teacherId(t.getTeacher() != null ? t.getTeacher().getId() : null)
            .teacherName(t.getTeacher() != null
                ? t.getTeacher().getFirstName() + " " + t.getTeacher().getLastName() : null)
            .classroomId(t.getClassroom() != null ? t.getClassroom().getId() : null)
            .roomName(t.getClassroom() != null ? t.getClassroom().getRoomName() : null)
            .roomNumber(t.getClassroom() != null ? t.getClassroom().getRoomNumber() : null)
            .dayOfWeek(t.getDayOfWeek()).startTime(t.getStartTime()).endTime(t.getEndTime())
            .academicYear(t.getAcademicYear()).createdAt(t.getCreatedAt()).build();
    }

    private Timetable buildTimetable(Timetable t, TimetableRequest req) {
        t.setDayOfWeek(req.getDayOfWeek());
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
}
