package com.crm.service;

import com.crm.dto.request.ClassroomRequest;
import com.crm.dto.response.ClassroomResponse;
import com.crm.entity.Classroom;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.ClassroomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClassroomService {

    private final ClassroomRepository classroomRepository;

    @Transactional(readOnly = true)
    public List<ClassroomResponse> getAll() {
        return classroomRepository.findAllByOrderByRoomNumberAsc().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ClassroomResponse getById(Long id) {
        return toResponse(classroomRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Classroom", id)));
    }

    @Transactional
    public ClassroomResponse create(ClassroomRequest req) {
        if (classroomRepository.existsByRoomNumberIgnoreCase(req.getRoomNumber().trim())) {
            throw new BadRequestException(
                "Bu xona raqami allaqachon mavjud: " + req.getRoomNumber());
        }
        Classroom c = new Classroom();
        c.setRoomNumber(req.getRoomNumber().trim());
        c.setRoomName(req.getRoomNumber().trim());
        c.setCapacity(req.getCapacity());
        c.setRoomType(req.getRoomType() != null ? req.getRoomType() : "THEORY");
        c.setDescription(req.getDescription());
        c.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);
        return toResponse(classroomRepository.save(c));
    }

    @Transactional
    public ClassroomResponse update(Long id, ClassroomRequest req) {
        Classroom c = classroomRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Classroom", id));
        if (req.getRoomNumber() != null) {
            String rn = req.getRoomNumber().trim();
            if (classroomRepository.existsByRoomNumberIgnoreCaseAndIdNot(rn, id)) {
                throw new BadRequestException("Bu xona raqami allaqachon mavjud: " + rn);
            }
            c.setRoomNumber(rn);
            c.setRoomName(rn);
        }
        if (req.getCapacity() != null) {
            c.setCapacity(req.getCapacity());
        }
        if (req.getRoomType() != null) {
            c.setRoomType(req.getRoomType());
        }
        if (req.getDescription() != null) {
            c.setDescription(req.getDescription());
        }
        if (req.getIsActive() != null) {
            c.setIsActive(req.getIsActive());
        }
        return toResponse(classroomRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        if (!classroomRepository.existsById(id)) {
            throw new ResourceNotFoundException("Classroom", id);
        }
        classroomRepository.deleteById(id);
    }

    private ClassroomResponse toResponse(Classroom c) {
        return ClassroomResponse.builder()
            .id(c.getId())
            .roomNumber(c.getRoomNumber())
            .capacity(c.getCapacity())
            .roomType(c.getRoomType())
            .description(c.getDescription())
            .isActive(c.getIsActive())
            .createdAt(c.getCreatedAt())
            .build();
    }
}
