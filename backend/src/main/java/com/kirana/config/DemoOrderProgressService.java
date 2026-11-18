package com.kirana.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.request.OrderService;
import com.kirana.request.RequestStatus;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Demo helper: after the customer accepts a quote, auto mark READY then PICKED_UP
 * so testers can reach the rating / thank-you screen without a second device.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoOrderProgressService {

    private final OrderService orderService;
    private final OrderRequestRepository requests;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "demo-order-progress");
        t.setDaemon(true);
        return t;
    });

    @Value("${app.demo.auto-progress:true}")
    private boolean enabled;

    @Value("${app.demo.auto-ready-after-seconds:3}")
    private int readyAfterSec;

    @Value("${app.demo.auto-pickup-after-seconds:4}")
    private int pickupAfterSec;

    /** Fire-and-forget: READY after a short delay, then PICKED_UP with the real OTP. */
    public void scheduleAfterAccept(Long requestId, Long storeOwnerId) {
        if (!enabled || requestId == null || storeOwnerId == null) {
            return;
        }
        int readyDelay = Math.max(1, readyAfterSec);
        int pickupDelay = readyDelay + Math.max(1, pickupAfterSec);
        scheduler.schedule(() -> markReadyQuiet(storeOwnerId, requestId), readyDelay, TimeUnit.SECONDS);
        scheduler.schedule(() -> markPickedUpQuiet(storeOwnerId, requestId), pickupDelay, TimeUnit.SECONDS);
    }

    private void markReadyQuiet(Long ownerId, Long requestId) {
        try {
            orderService.markReady(ownerId, requestId);
            log.info("Demo auto-ready for request #{}", requestId);
        } catch (Exception ex) {
            log.warn("Demo auto-ready skipped for request #{}: {}", requestId, ex.getMessage());
        }
    }

    private void markPickedUpQuiet(Long ownerId, Long requestId) {
        try {
            OrderRequest r = requests.findById(requestId).orElse(null);
            if (r == null || r.getStatus() != RequestStatus.READY || r.getPickupCode() == null) {
                log.warn("Demo auto-pickup skipped for request #{} (status={})",
                        requestId, r == null ? "missing" : r.getStatus());
                return;
            }
            orderService.markPickedUp(ownerId, requestId, r.getPickupCode());
            log.info("Demo auto-pickup for request #{}", requestId);
        } catch (Exception ex) {
            log.warn("Demo auto-pickup skipped for request #{}: {}", requestId, ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
