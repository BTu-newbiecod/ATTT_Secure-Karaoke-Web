package com.karaoke.backend.service.impl;

import com.karaoke.backend.dto.response.*;
import com.karaoke.backend.entity.*;
import com.karaoke.backend.exception.BusinessException;
import com.karaoke.backend.exception.ResourceNotFoundException;
import com.karaoke.backend.repository.InvoiceRepository;
import com.karaoke.backend.repository.RoomPriceRepository;
import com.karaoke.backend.repository.RoomPriceSpecialRepository;
import com.karaoke.backend.repository.SystemConfigRepository;
import com.karaoke.backend.service.InvoiceService;
import com.karaoke.backend.service.KaraokePricingEngine;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;
import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService
{
    private final InvoiceRepository invoiceRepository;
    private final KaraokePricingEngine pricingEngine;
    private final RoomPriceRepository roomPriceRepository;
    private final RoomPriceSpecialRepository roomPriceSpecialRepository;
    private final SystemConfigRepository systemConfigRepository;

    @Override
    @Transactional
    public Page<InvoiceSummaryResponse> getInvoices(String statusStr, String keyword, LocalDate date, int page, int size)
    {
        Invoice.InvoiceStatus statusEnum = null;
        if (statusStr != null && !statusStr.trim().isEmpty())
        {
            try
            {
                statusEnum = Invoice.InvoiceStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {}
        }

        LocalDateTime startDateTime = null;
        LocalDateTime endDateTime = null;

        if (date != null)
        {
            startDateTime = date.atStartOfDay();
            endDateTime = date.atTime(23, 59, 59, 999999);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Invoice> invoicePage = invoiceRepository.searchInvoices(
                statusEnum, keyword, startDateTime, endDateTime, pageable);

        return invoicePage.map(invoice -> {
            InvoiceSummaryResponse dto = new InvoiceSummaryResponse();
            dto.setInvoiceId(invoice.getId());
            dto.setBookingId(invoice.getBooking().getId());
            dto.setStatus(invoice.getStatus().name());
            dto.setCreatedAt(invoice.getCreatedAt());

            if (invoice.getBooking().getCustomer() != null) {
                dto.setCustomerName(invoice.getBooking().getCustomer().getName());
                dto.setCustomerPhone(invoice.getBooking().getCustomer().getPhone());
                dto.setCustomerIdentity(invoice.getBooking().getCustomer().getIdentity());
            }

            String roomNames = invoice.getBooking().getBookingRooms().stream()
                    .filter(br -> !br.getStatus().name().equals("CANCELLED"))
                    .map(br -> br.getRoom().getName())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            dto.setRoomNames(roomNames);

            return dto;
        });
    }

    @Override
    @Transactional
    public InvoiceDetailResponse getInvoiceDetail(Integer id)
    {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hóa đơn ID: " + id));

        InvoiceDetailResponse response = new InvoiceDetailResponse();
        response.setInvoiceId(invoice.getId());
        response.setBookingId(invoice.getBooking().getId());
        response.setStatus(invoice.getStatus().name());
        response.setCreatedAt(invoice.getCreatedAt());
        response.setTotalRoomPrice(invoice.getRoomPrice());
        response.setTotalServicePrice(invoice.getServicePrice());
        response.setDiscountPercent(invoice.getDiscountPercent());
        response.setDiscount(invoice.getDiscount());
        response.setTotalPrice(invoice.getTotalPrice());

        Customer customer = invoice.getBooking().getCustomer();
        if (customer != null) {
            response.setCustomerName(customer.getName());
            response.setCustomerPhone(customer.getPhone());
            response.setCustomerIdentity(customer.getIdentity());
        }

        boolean isUnpaid = invoice.getStatus().name().equals("UNPAID");
        LocalDateTime now = LocalDateTime.now();

        List<InvoiceRoomDetailDto> roomDetails = new java.util.ArrayList<>();
        BigDecimal totalRoomPriceCalc = BigDecimal.ZERO;
        BigDecimal totalServicePriceCalc = BigDecimal.ZERO;

        if (isUnpaid)
        {
            for (com.karaoke.backend.entity.BookingRoom br : invoice.getBooking().getBookingRooms()) {
                if (br.getStatus().name().equals("CANCELLED") || br.getStatus().name().equals("RESERVED")) continue;

                InvoiceRoomDetailDto dto = new InvoiceRoomDetailDto();
                dto.setId(null);
                dto.setBookingRoomId(br.getId());
                dto.setRoomName(br.getRoom().getName());
                dto.setPricePerHour(calculateCurrentRoomPrice(br.getRoom().getId()));
                dto.setCheckinTime(br.getCheckinTime());
                
                LocalDateTime endCalc = br.getCheckoutTime() != null ? br.getCheckoutTime() : now;
                dto.setCheckoutTime(endCalc);

                if (br.getCheckinTime() != null) {
                    PricingCalculationResult calcResult = pricingEngine.calculateRoomPriceWithSlices(
                            br.getRoom().getId(), br.getCheckinTime(), endCalc
                    );
                    dto.setHoursUsed(calcResult.getTotalHours());
                    dto.setTotalAmount(calcResult.getTotalCost());
                    dto.setPriceBreakdowns(calcResult.getSlices());
                } else {
                    dto.setHoursUsed(BigDecimal.ZERO);
                    dto.setTotalAmount(BigDecimal.ZERO);
                }
                totalRoomPriceCalc = totalRoomPriceCalc.add(dto.getTotalAmount() != null ? dto.getTotalAmount() : BigDecimal.ZERO);
                roomDetails.add(dto);
            }
        } else {
            roomDetails = invoice.getRoomDetails().stream().map(ird -> {
                InvoiceRoomDetailDto dto = new InvoiceRoomDetailDto();
                dto.setId(ird.getId());
                dto.setBookingRoomId(ird.getBookingRoom().getId());
                dto.setRoomName(ird.getBookingRoom().getRoom().getName());
                dto.setCheckinTime(ird.getBookingRoom().getCheckinTime());
                dto.setCheckoutTime(ird.getBookingRoom().getCheckoutTime());
                dto.setHoursUsed(ird.getHoursUsed());
                dto.setTotalAmount(ird.getTotalPrice());
                dto.setPricePerHour(ird.getPricePerHour());

                List<TimeSliceDto> historicalSlices = ird.getSlices().stream().map(s -> {
                    TimeSliceDto sliceDto = new TimeSliceDto();
                    sliceDto.setStartTime(s.getStartTime());
                    sliceDto.setEndTime(s.getEndTime());
                    sliceDto.setPricePerHour(s.getPricePerHour());
                    sliceDto.setHours(s.getHoursUsed());
                    sliceDto.setAmount(s.getTotalAmount());
                    return sliceDto;
                }).collect(Collectors.toList());
                dto.setPriceBreakdowns(historicalSlices);
                return dto;
            }).collect(Collectors.toList());
        }
        response.setRoomDetails(roomDetails);

        // Product
        List<InvoiceItemDetail> itemDetails = invoice.getItems().stream().map(ii -> {
            InvoiceItemDetail dto = new InvoiceItemDetail();
            dto.setId(ii.getId());
            dto.setProductId(ii.getProduct().getId());
            dto.setProductName(ii.getProduct().getName());
            if (ii.getBookingRoom() != null) {
                dto.setBookingRoomId(ii.getBookingRoom().getId());
                dto.setRoomName(ii.getBookingRoom().getRoom().getName());
            }
            dto.setQuantity(ii.getQuantity());
            dto.setUnitPrice(ii.getUnitPrice());
            dto.setTotalAmount(ii.getTotalPrice());
            return dto;
        }).collect(Collectors.toList());
        response.setItemDetails(itemDetails);

        if (isUnpaid)
        {
            response.setTotalRoomPrice(totalRoomPriceCalc);
            for (InvoiceItemDetail item : itemDetails) {
                if (item.getTotalAmount() != null) {
                    totalServicePriceCalc = totalServicePriceCalc.add(item.getTotalAmount());
                }
            }
            response.setTotalServicePrice(totalServicePriceCalc);
            
            BigDecimal discount = response.getDiscount() != null ? response.getDiscount() : BigDecimal.ZERO;
            BigDecimal finalPrice = totalRoomPriceCalc.add(totalServicePriceCalc).subtract(discount);
            if (finalPrice.compareTo(BigDecimal.ZERO) < 0)
            {
                finalPrice = BigDecimal.ZERO;
            }
            response.setTotalPrice(finalPrice);
        }

        return response;
    }

    @Transactional
    @Override
    public void markAsPaid(Integer invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hóa đơn!"));

        if (invoice.getStatus() == Invoice.InvoiceStatus.PAID)
        {
            return;
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);

        BigDecimal totalRoomPrice = BigDecimal.ZERO;
        for (com.karaoke.backend.entity.BookingRoom br : invoice.getBooking().getBookingRooms())
        {
            if (br.getStatus().name().equals("CANCELLED") || br.getStatus().name().equals("RESERVED")) continue;

            LocalDateTime endCalc = br.getCheckoutTime() != null ? br.getCheckoutTime() : now;
            
            InvoiceRoomDetail ird = new InvoiceRoomDetail();
            ird.setInvoice(invoice);
            ird.setBookingRoom(br);
            
            if (br.getCheckinTime() != null)
            {
                PricingCalculationResult calcResult = pricingEngine.calculateRoomPriceWithSlices(
                        br.getRoom().getId(), br.getCheckinTime(), endCalc
                );
                ird.setHoursUsed(calcResult.getTotalHours());
                ird.setTotalPrice(calcResult.getTotalCost());
                
                for (com.karaoke.backend.dto.response.TimeSliceDto sliceDto : calcResult.getSlices()) {
                    InvoiceRoomDetailSlice slice = new InvoiceRoomDetailSlice();
                    slice.setInvoiceRoomDetail(ird);
                    slice.setStartTime(sliceDto.getStartTime());
                    slice.setEndTime(sliceDto.getEndTime());
                    slice.setPricePerHour(sliceDto.getPricePerHour());
                    slice.setHoursUsed(sliceDto.getHours());
                    slice.setTotalAmount(sliceDto.getAmount());
                    ird.getSlices().add(slice);
                }

                if (!calcResult.getSlices().isEmpty()) {
                    ird.setPricePerHour(calcResult.getSlices().get(0).getPricePerHour());
                }
            } else {
                ird.setHoursUsed(BigDecimal.ZERO);
                ird.setTotalPrice(BigDecimal.ZERO);
            }
            totalRoomPrice = totalRoomPrice.add(ird.getTotalPrice() != null ? ird.getTotalPrice() : BigDecimal.ZERO);
            invoice.getRoomDetails().add(ird);
        }

        BigDecimal totalServicePrice = BigDecimal.ZERO;
        for (InvoiceItem item : invoice.getItems())
        {
            totalServicePrice = totalServicePrice.add(item.getTotalPrice() != null ? item.getTotalPrice() : BigDecimal.ZERO);
        }

        invoice.setRoomPrice(totalRoomPrice);
        invoice.setServicePrice(totalServicePrice);

        BigDecimal discount = invoice.getDiscount() != null ? invoice.getDiscount() : BigDecimal.ZERO;
        BigDecimal finalPrice = totalRoomPrice.add(totalServicePrice).subtract(discount);
        if (finalPrice.compareTo(BigDecimal.ZERO) < 0) {
            finalPrice = BigDecimal.ZERO;
        }
        invoice.setTotalPrice(finalPrice);

        invoice.setStatus(Invoice.InvoiceStatus.PAID);
        invoice.setPaidAt(now);

        // Calculate Chaining Hash (SHA-256)
        String previousHash = "secure-karaoke-genesis-hash-value-00000000000000000";
        Invoice prevInvoice = invoiceRepository.findFirstByStatusAndIdLessThanOrderByIdDesc(
                Invoice.InvoiceStatus.PAID, invoice.getId()
        );
        if (prevInvoice != null && prevInvoice.getHashValue() != null) {
            previousHash = prevInvoice.getHashValue();
        }

        String dataStr = String.format(java.util.Locale.US, "%d|%d|%.2f|%.2f|%.2f|%.2f|%s",
                invoice.getId(),
                invoice.getBooking().getId(),
                invoice.getRoomPrice() != null ? invoice.getRoomPrice() : BigDecimal.ZERO,
                invoice.getServicePrice() != null ? invoice.getServicePrice() : BigDecimal.ZERO,
                invoice.getDiscount() != null ? invoice.getDiscount() : BigDecimal.ZERO,
                invoice.getTotalPrice() != null ? invoice.getTotalPrice() : BigDecimal.ZERO,
                invoice.getPaidAt() != null ? invoice.getPaidAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) : ""
        );
        String currentHash = com.karaoke.backend.util.CryptoUtils.sha256(dataStr + previousHash);
        
//        System.out.println("====== DEBUG [markAsPaid] ======");
//        System.out.println("Invoice ID: " + invoice.getId());
//        System.out.println("Booking ID: " + invoice.getBooking().getId());
//        System.out.println("Room Price: " + invoice.getRoomPrice());
//        System.out.println("Service Price: " + invoice.getServicePrice());
//        System.out.println("Discount: " + invoice.getDiscount());
//        System.out.println("Total Price: " + invoice.getTotalPrice());
//        System.out.println("Paid At (Raw): " + invoice.getPaidAt());
//        System.out.println("Paid At (Formatted): " + (invoice.getPaidAt() != null ? invoice.getPaidAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) : ""));
//        System.out.println("dataStr: " + dataStr);
//        System.out.println("previousHash: " + previousHash);
//        System.out.println("currentHash: " + currentHash);
//        System.out.println("=================================");

        invoice.setHashValue(currentHash);

        // RSA Encryption of Total Amount
        try {
            SystemConfig config = systemConfigRepository.findByConfigKey("RSA_MASTER_PUBLIC_KEY")
                    .orElseThrow(() -> new BusinessException("Không tìm thấy khóa công khai hệ thống (RSA_MASTER_PUBLIC_KEY) trong database! Vui lòng khởi tạo cặp khóa trong mục Quản lý khóa trước."));

            String pubKeyPem = config.getConfigValue();
            if (pubKeyPem == null || pubKeyPem.trim().isEmpty()) {
                throw new BusinessException("Khóa công khai hệ thống (RSA_MASTER_PUBLIC_KEY) trống rỗng! Vui lòng khởi tạo lại khóa.");
            }

            java.security.PublicKey publicKey = com.karaoke.backend.util.CryptoUtils.parsePublicKeyPem(pubKeyPem);
            String encryptedAmt = com.karaoke.backend.util.CryptoUtils.rsaEncrypt(invoice.getTotalPrice().toPlainString(), publicKey);
            invoice.setEncryptedAmount(encryptedAmt);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi mã hóa bảo mật số tiền hóa đơn bằng RSA: " + e.getMessage(), e);
        }

        invoiceRepository.save(invoice);
    }

    @Override
    @Transactional
    public void applyPercentageDiscount(Integer invoiceId, BigDecimal discountPercent)
    {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hóa đơn"));

        if (discountPercent.compareTo(BigDecimal.ZERO) < 0 || discountPercent.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("Phần trăm giảm giá phải nằm trong khoảng từ 0 đến 100!");
        }

        invoice.setDiscountPercent(discountPercent);

        invoiceRepository.save(invoice);
    }

    @Override
    @Transactional
    public void applyDirectDiscount(Integer invoiceId, BigDecimal discountAmount)
    {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hóa đơn"));

        if (discountAmount.compareTo(BigDecimal.ZERO) < 0)
        {
            throw new IllegalArgumentException("Số tiền giảm không được nhỏ hơn 0!");
        }

        // Calculate current subtotal
        BigDecimal currentSubtotal = BigDecimal.ZERO;
        for (com.karaoke.backend.entity.BookingRoom br : invoice.getBooking().getBookingRooms())
        {
            if (br.getStatus().name().equals("CANCELLED") || br.getStatus().name().equals("RESERVED")) continue;
            LocalDateTime endCalc = br.getCheckoutTime() != null ? br.getCheckoutTime() : LocalDateTime.now();
            PricingCalculationResult calcResult = pricingEngine.calculateRoomPriceWithSlices(
                    br.getRoom().getId(), br.getCheckinTime(), endCalc
            );
            currentSubtotal = currentSubtotal.add(calcResult.getTotalCost());
        }
        for (InvoiceItem item : invoice.getItems()) {
            currentSubtotal = currentSubtotal.add(item.getTotalPrice() != null ? item.getTotalPrice() : BigDecimal.ZERO);
        }

        if (discountAmount.compareTo(currentSubtotal) > 0)
        {
            throw new IllegalArgumentException("Số tiền giảm (" + discountAmount + ") không được lớn hơn tổng hóa đơn (" + currentSubtotal + ")!");
        }

        invoice.setDiscount(discountAmount);
        invoiceRepository.save(invoice);
    }

    public BigDecimal calculateCurrentRoomPrice(Integer roomId)
    {
        LocalDateTime now = LocalDateTime.now();
        LocalTime currentTime = now.toLocalTime();
        LocalDate today = now.toLocalDate();
        String dayOfWeekStr = now.getDayOfWeek().name().substring(0, 3).toUpperCase();
        RoomPrice.DayOfWeek dayEnum = RoomPrice.DayOfWeek.valueOf(dayOfWeekStr);

        Double specialPrice = roomPriceSpecialRepository.findPrice(roomId, today, currentTime);
        if (specialPrice != null) return BigDecimal.valueOf(specialPrice);

        Double normalPrice = roomPriceRepository.findPrice(roomId, dayEnum, currentTime);
        if (normalPrice != null) return BigDecimal.valueOf(normalPrice);

        return BigDecimal.valueOf(0.0);
    }
}
