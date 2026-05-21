package com.karaoke.backend.security.service;

import com.karaoke.backend.dto.response.VerifyChainResponseDto;
import com.karaoke.backend.dto.response.VerifyRecoverResponseDto;
import org.springframework.data.domain.Pageable;

public interface InvoiceSecurityService {

    String generateKeyPair();

    VerifyChainResponseDto verifyInvoiceChain(Pageable pageable);

    VerifyRecoverResponseDto verifyAndRecoverAmounts(String privateKeyPem, Pageable pageable);

    void migrateLegacyInvoices();
}
