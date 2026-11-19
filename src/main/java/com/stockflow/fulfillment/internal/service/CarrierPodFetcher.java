package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Keeps the proof of delivery a carrier's webhook pointed at (kltn-docs 09 §4.3 step 2: "POD đính kèm shipment: hãng
 * gửi kèm sự kiện"; §4.5 step 7): after the event committed, the file is fetched once from the https address the
 * carrier gave and kept with each shipment the event delivered, through {@link ShipmentPodFiles#keepFromCarrier} - the
 * checks, scan and storage of an uploaded POD.
 *
 * <p>A POD is optional (BR-28, E16): the delivery stands without it. A file that cannot be had - the address refused,
 * not a PDF or an image, larger than the documents category takes - is logged and left; the warehouse manager uploads
 * one by hand. Only a public address is fetched: the carrier's event is signed, but an address inside the network is
 * never one a carrier keeps files at. Redirects are not followed (the JDK client's default).</p>
 */
@Component
class CarrierPodFetcher {

    private static final Logger log = LoggerFactory.getLogger(CarrierPodFetcher.class);

    /** What a fetch gives: the bytes, their declared type and a file name. */
    record Fetched(byte[] bytes, String contentType, String fileName) { }

    /** Fetches a file; replaced in the tests, which never reach a carrier. */
    interface Download {
        Fetched fetch(URI uri) throws IOException;
    }

    private final ShipmentPodFiles pods;
    private Download download;

    CarrierPodFetcher(ShipmentPodFiles pods, RestClientFactory clients) {
        this.pods = pods;
        this.download = httpDownload(clients.forService("carrier-pod", "").build());
    }

    /** For the tests: the fetch to run in place of the real one. */
    void fetchWith(Download replacement) {
        this.download = replacement;
    }

    @ApplicationModuleListener
    public void on(CarrierPodReported event) {
        Fetched file;
        try {
            file = download.fetch(URI.create(event.podUrl()));
        } catch (IOException | RestClientException | IllegalArgumentException unreachable) {
            log.warn("Carrier event {}: the proof of delivery could not be fetched ({}); none kept",
                    event.carrierEventId(), unreachable.getMessage());
            return;
        }
        for (UUID shipmentId : event.shipmentIds()) {
            try {
                boolean kept = pods.keepFromCarrier(shipmentId, event.carrierEventId(), new FileUpload(file.fileName(),
                        file.contentType(), file.bytes().length, new ByteArrayInputStream(file.bytes())));
                log.info("Carrier event {}: proof of delivery {} for shipment {}", event.carrierEventId(),
                        kept ? "kept" : "kept already or no longer delivered", shipmentId);
            } catch (BusinessException refused) {
                log.warn("Carrier event {}: the proof of delivery was refused for shipment {} ({})",
                        event.carrierEventId(), shipmentId, refused.getMessage());
            }
        }
    }

    /** The real fetch: a public https address, its body read up to what the documents category takes. */
    static Download httpDownload(RestClient client) {
        return uri -> {
            requirePublic(uri);
            return client.get().uri(uri).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (!status.is2xxSuccessful()) {
                    throw new IOException("the carrier's address answered " + status.value());
                }
                MediaType type = response.getHeaders().getContentType();
                try (InputStream body = response.getBody()) {
                    byte[] bytes = body.readNBytes((int) FileCategory.DOCUMENT.maxBytes() + 1);
                    if (bytes.length == 0 || bytes.length > FileCategory.DOCUMENT.maxBytes()) {
                        throw new IOException("the file is empty or larger than a document may be");
                    }
                    return new Fetched(bytes, type == null ? null : type.getType() + "/" + type.getSubtype(),
                            fileName(uri));
                }
            });
        };
    }

    /** An https address whose host resolves only to public addresses. */
    static void requirePublic(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("not an https address");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException unknown) {
            throw new IOException("unknown host", unknown);
        }
        for (InetAddress address : resolved) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || isUniqueLocal(address)) {
                throw new IOException("the address is not a public one");
            }
        }
    }

    /** IPv6 unique local addresses (fc00::/7), which {@link InetAddress#isSiteLocalAddress} does not cover. */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
    }

    /** The last segment of the path, or a fixed name: the file is kept under it and saved under it later. */
    static String fileName(URI uri) {
        String path = uri.getPath();
        String last = path == null ? "" : path.substring(path.lastIndexOf('/') + 1).strip();
        return last.isEmpty() || last.length() > 255 ? "pod" : last;
    }
}
