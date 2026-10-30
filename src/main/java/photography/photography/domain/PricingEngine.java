package photography.photography.domain;

import java.time.LocalDate;
import java.util.List;

import photography.photography.api.ApiException;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.Values.AddOn;
import photography.photography.model.Values.CancellationPolicy;
import photography.photography.model.Values.PriceBreakdown;
import photography.photography.model.Values.PriceInfo;
import photography.photography.model.Values.PromoCode;

/** Server-authoritative pricing, refund policy and the marketplace "starting price". The client never decides a total. */
public final class PricingEngine {
    private PricingEngine() {
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static PriceBreakdown price(ServiceOffer svc, ServicePackage pk, List<String> addOnIds, String promoCode, Double hours,
                                       List<PromoCode> promos, double commissionPercent, LocalDate today) {
        if (!"published".equals(svc.getStatus()) || !"published".equals(pk.getStatus()) || "hidden".equals(svc.getModeration())) {
            throw ApiException.conflict("This package is no longer available.");
        }
        double base;
        if ("quote".equals(svc.getPricingModel()) || pk.getPrice() == null) {
            base = 0;
        } else if ("hourly".equals(svc.getPricingModel())) {
            double h = hours != null ? hours : pk.getDurationMinutes() / 60.0;
            if (!(h >= 0.5 && h <= 24)) {
                throw ApiException.validation("Hours must be between 0.5 and 24.", "hours", "Between 0.5 and 24");
            }
            base = round2(pk.getPrice() * h);
        } else {
            base = pk.getPrice();
        }
        double addTotal = 0;
        if (addOnIds != null) {
            for (AddOn a : svc.getAddOns()) {
                if (addOnIds.contains(a.id())) {
                    addTotal += a.price();
                }
            }
        }
        double discount = 0;
        String appliedCode = null;
        if (promoCode != null && !promoCode.isBlank()) {
            String wanted = promoCode.trim();
            PromoCode promo = promos.stream().filter(c -> c.active() && c.code().equalsIgnoreCase(wanted)
                    && (c.maxUses() == null || c.maxUses() == 0 || c.uses() < c.maxUses())
                    && (c.expiresAt() == null || c.expiresAt().isBlank() || c.expiresAt().compareTo(today.toString()) >= 0)).findFirst().orElse(null);
            if (promo == null) {
                throw ApiException.validation("Promo code is invalid or expired.", "promoCode", "Invalid or expired code");
            }
            discount = Math.round(((base + addTotal) * promo.percent()) / 100.0);
            appliedCode = promo.code();
        }
        double subtotal = Math.max(0, round2(base + addTotal - discount));
        double fee = Math.round(subtotal * (commissionPercent / 100.0));
        return new PriceBreakdown(svc.getPricingModel(), base, round2(addTotal), discount, appliedCode, subtotal, fee, subtotal, "MAD",
                "q-" + pk.getId() + "-" + Math.round(subtotal * 100));
    }

    /** Percentage of the booking value refunded when a booking is cancelled. Photographer/admin cancellations and unconfirmed requests refund fully. */
    public static int refundPercent(String cancellerRole, String bookingStatus, double hoursUntilSession, CancellationPolicy policy) {
        if (!"client".equals(cancellerRole) || "pending".equals(bookingStatus)) {
            return 100;
        }
        if (hoursUntilSession >= policy.freeCancelHours()) {
            return 100;
        }
        if (hoursUntilSession >= policy.partialRefundHours()) {
            return policy.partialRefundPercent();
        }
        return 0;
    }

    /** What the marketplace shows as "starting price": never conflates fixed, hourly and quote pricing. */
    public static PriceInfo startingPrice(List<ServiceOffer> services, List<ServicePackage> packages) {
        double fixedMin = Double.MAX_VALUE;
        double hourlyMin = Double.MAX_VALUE;
        for (ServiceOffer s : services) {
            if (!"published".equals(s.getStatus()) || "hidden".equals(s.getModeration())) {
                continue;
            }
            List<Double> prices = packages.stream().filter(p -> s.getId().equals(p.getServiceId()) && "published".equals(p.getStatus()) && p.getPrice() != null)
                    .map(ServicePackage::getPrice).toList();
            if (prices.isEmpty() && s.getBasePrice() != null) {
                prices = List.of(s.getBasePrice());
            }
            for (double p : prices) {
                if ("hourly".equals(s.getPricingModel())) {
                    hourlyMin = Math.min(hourlyMin, p);
                } else if (!"quote".equals(s.getPricingModel())) {
                    fixedMin = Math.min(fixedMin, p);
                }
            }
        }
        if (fixedMin != Double.MAX_VALUE) {
            return new PriceInfo(fixedMin, "MAD", "starting", "from");
        }
        if (hourlyMin != Double.MAX_VALUE) {
            return new PriceInfo(hourlyMin, "MAD", "hourly", "per_hour");
        }
        return new PriceInfo(null, "MAD", "quote", "quote");
    }
}
