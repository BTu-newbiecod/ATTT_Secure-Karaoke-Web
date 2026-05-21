package com.karaoke.backend;

import com.karaoke.backend.entity.Invoice;
import com.karaoke.backend.repository.InvoiceRepository;
import com.karaoke.backend.security.service.impl.InvoiceSecurityServiceImpl;
import com.karaoke.backend.util.CryptoUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest
class BackendApplicationTests {

	@Autowired
	private InvoiceRepository invoiceRepository;

	@Autowired
	private InvoiceSecurityServiceImpl invoiceSecurityService;

	@Test
	void testInvoiceChainDiagnostics() throws Exception {
		System.out.println("========== INVOICE CHAIN DIAGNOSTICS ==========");
		List<Invoice> paidInvoices = invoiceRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
				.filter(i -> i.getStatus() == Invoice.InvoiceStatus.PAID)
				.collect(Collectors.toList());

		System.out.println("Total Paid Invoices: " + paidInvoices.size());

		// Access private buildInvoiceDataString using reflection
		Method buildInvoiceDataStringMethod = InvoiceSecurityServiceImpl.class.getDeclaredMethod("buildInvoiceDataString", Invoice.class);
		buildInvoiceDataStringMethod.setAccessible(true);

		String previousHash = "secure-karaoke-genesis-hash-value-00000000000000000";

		for (Invoice invoice : paidInvoices) {
			String dataStr = (String) buildInvoiceDataStringMethod.invoke(invoiceSecurityService, invoice);
			String expectedHash = CryptoUtils.sha256(dataStr + previousHash);
			boolean matches = expectedHash.equals(invoice.getHashValue());

			System.out.println("Invoice ID: " + invoice.getId());
			System.out.println("  Booking ID:     " + (invoice.getBooking() != null ? invoice.getBooking().getId() : "null"));
			System.out.println("  Room Price:     " + invoice.getRoomPrice());
			System.out.println("  Service Price:  " + invoice.getServicePrice());
			System.out.println("  Discount:       " + invoice.getDiscount());
			System.out.println("  Total Price:    " + invoice.getTotalPrice());
			System.out.println("  Paid At:        " + invoice.getPaidAt());
			System.out.println("  dataStr:        \"" + dataStr + "\"");
			System.out.println("  previousHash:   " + previousHash);
			System.out.println("  Stored Hash:    " + invoice.getHashValue());
			System.out.println("  Expected Hash:  " + expectedHash);
			System.out.println("  Matches?        " + matches);
			System.out.println("------------------------------------------------");

			// Update previousHash to check downstream matching
			previousHash = invoice.getHashValue() != null ? invoice.getHashValue() : expectedHash;
		}
		System.out.println("===============================================");
	}

}
