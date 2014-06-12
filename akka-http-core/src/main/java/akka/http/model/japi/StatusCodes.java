/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.StatusCodes$;
import akka.japi.Option;

/**
 * Contains the set of predefined status-codes along with static methods to access and create custom
 * status-codes.
 */
public final class StatusCodes {
    private StatusCodes() {}

    public static final StatusCode Continue = akka.http.model.StatusCodes.Continue();
    public static final StatusCode SwitchingProtocols = akka.http.model.StatusCodes.SwitchingProtocols();
    public static final StatusCode Processing = akka.http.model.StatusCodes.Processing();

    public static final StatusCode OK = akka.http.model.StatusCodes.OK();
    public static final StatusCode Created = akka.http.model.StatusCodes.Created();
    public static final StatusCode Accepted = akka.http.model.StatusCodes.Accepted();
    public static final StatusCode NonAuthoritativeInformation = akka.http.model.StatusCodes.NonAuthoritativeInformation();
    public static final StatusCode NoContent = akka.http.model.StatusCodes.NoContent();
    public static final StatusCode ResetContent = akka.http.model.StatusCodes.ResetContent();
    public static final StatusCode PartialContent = akka.http.model.StatusCodes.PartialContent();
    public static final StatusCode MultiStatus = akka.http.model.StatusCodes.MultiStatus();
    public static final StatusCode AlreadyReported = akka.http.model.StatusCodes.AlreadyReported();
    public static final StatusCode IMUsed = akka.http.model.StatusCodes.IMUsed();

    public static final StatusCode MultipleChoices = akka.http.model.StatusCodes.MultipleChoices();
    public static final StatusCode MovedPermanently = akka.http.model.StatusCodes.MovedPermanently();
    public static final StatusCode Found = akka.http.model.StatusCodes.Found();
    public static final StatusCode SeeOther = akka.http.model.StatusCodes.SeeOther();
    public static final StatusCode NotModified = akka.http.model.StatusCodes.NotModified();
    public static final StatusCode UseProxy = akka.http.model.StatusCodes.UseProxy();
    public static final StatusCode TemporaryRedirect = akka.http.model.StatusCodes.TemporaryRedirect();
    public static final StatusCode PermanentRedirect = akka.http.model.StatusCodes.PermanentRedirect();

    public static final StatusCode BadRequest = akka.http.model.StatusCodes.BadRequest();
    public static final StatusCode Unauthorized = akka.http.model.StatusCodes.Unauthorized();
    public static final StatusCode PaymentRequired = akka.http.model.StatusCodes.PaymentRequired();
    public static final StatusCode Forbidden = akka.http.model.StatusCodes.Forbidden();
    public static final StatusCode NotFound = akka.http.model.StatusCodes.NotFound();
    public static final StatusCode MethodNotAllowed = akka.http.model.StatusCodes.MethodNotAllowed();
    public static final StatusCode NotAcceptable = akka.http.model.StatusCodes.NotAcceptable();
    public static final StatusCode ProxyAuthenticationRequired = akka.http.model.StatusCodes.ProxyAuthenticationRequired();
    public static final StatusCode RequestTimeout = akka.http.model.StatusCodes.RequestTimeout();
    public static final StatusCode Conflict = akka.http.model.StatusCodes.Conflict();
    public static final StatusCode Gone = akka.http.model.StatusCodes.Gone();
    public static final StatusCode LengthRequired = akka.http.model.StatusCodes.LengthRequired();
    public static final StatusCode PreconditionFailed = akka.http.model.StatusCodes.PreconditionFailed();
    public static final StatusCode RequestEntityTooLarge = akka.http.model.StatusCodes.RequestEntityTooLarge();
    public static final StatusCode RequestUriTooLong = akka.http.model.StatusCodes.RequestUriTooLong();
    public static final StatusCode UnsupportedMediaType = akka.http.model.StatusCodes.UnsupportedMediaType();
    public static final StatusCode RequestedRangeNotSatisfiable = akka.http.model.StatusCodes.RequestedRangeNotSatisfiable();
    public static final StatusCode ExpectationFailed = akka.http.model.StatusCodes.ExpectationFailed();
    public static final StatusCode EnhanceYourCalm = akka.http.model.StatusCodes.EnhanceYourCalm();
    public static final StatusCode UnprocessableEntity = akka.http.model.StatusCodes.UnprocessableEntity();
    public static final StatusCode Locked = akka.http.model.StatusCodes.Locked();
    public static final StatusCode FailedDependency = akka.http.model.StatusCodes.FailedDependency();
    public static final StatusCode UnorderedCollection = akka.http.model.StatusCodes.UnorderedCollection();
    public static final StatusCode UpgradeRequired = akka.http.model.StatusCodes.UpgradeRequired();
    public static final StatusCode PreconditionRequired = akka.http.model.StatusCodes.PreconditionRequired();
    public static final StatusCode TooManyRequests = akka.http.model.StatusCodes.TooManyRequests();
    public static final StatusCode RequestHeaderFieldsTooLarge = akka.http.model.StatusCodes.RequestHeaderFieldsTooLarge();
    public static final StatusCode RetryWith = akka.http.model.StatusCodes.RetryWith();
    public static final StatusCode BlockedByParentalControls = akka.http.model.StatusCodes.BlockedByParentalControls();
    public static final StatusCode UnavailableForLegalReasons = akka.http.model.StatusCodes.UnavailableForLegalReasons();

    public static final StatusCode InternalServerError = akka.http.model.StatusCodes.InternalServerError();
    public static final StatusCode NotImplemented = akka.http.model.StatusCodes.NotImplemented();
    public static final StatusCode BadGateway = akka.http.model.StatusCodes.BadGateway();
    public static final StatusCode ServiceUnavailable = akka.http.model.StatusCodes.ServiceUnavailable();
    public static final StatusCode GatewayTimeout = akka.http.model.StatusCodes.GatewayTimeout();
    public static final StatusCode HTTPVersionNotSupported = akka.http.model.StatusCodes.HTTPVersionNotSupported();
    public static final StatusCode VariantAlsoNegotiates = akka.http.model.StatusCodes.VariantAlsoNegotiates();
    public static final StatusCode InsufficientStorage = akka.http.model.StatusCodes.InsufficientStorage();
    public static final StatusCode LoopDetected = akka.http.model.StatusCodes.LoopDetected();
    public static final StatusCode BandwidthLimitExceeded = akka.http.model.StatusCodes.BandwidthLimitExceeded();
    public static final StatusCode NotExtended = akka.http.model.StatusCodes.NotExtended();
    public static final StatusCode NetworkAuthenticationRequired = akka.http.model.StatusCodes.NetworkAuthenticationRequired();
    public static final StatusCode NetworkReadTimeout = akka.http.model.StatusCodes.NetworkReadTimeout();
    public static final StatusCode NetworkConnectTimeout = akka.http.model.StatusCodes.NetworkConnectTimeout();

    /**
     * Registers a custom status code.
     */
    public static StatusCode registerCustom(int intValue, String reason, String defaultMessage, boolean isSuccess, boolean allowsEntity) {
        return akka.http.model.StatusCodes.registerCustom(intValue, reason, defaultMessage, isSuccess, allowsEntity);
    }

    /**
     * Registers a custom status code.
     */
    public static StatusCode registerCustom(int intValue, String reason, String defaultMessage) {
        return akka.http.model.StatusCodes.registerCustom(intValue, reason, defaultMessage);
    }

    /**
     * Looks up a status-code by numeric code. Throws an exception if no such status-code is found.
     */
    public static StatusCode get(int intValue) {
        return akka.http.model.StatusCode.int2StatusCode(intValue);
    }

    /**
     * Looks up a status-code by numeric code and returns Some(code). Returns None otherwise.
     */
    public static Option<StatusCode> lookup(int intValue) {
        return Util.lookupInRegistry(StatusCodes$.MODULE$, intValue);
    }
}
