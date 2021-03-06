/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.quickstarts.kitchensink.rest;

import com.google.common.collect.ImmutableMap;
import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.service.MemberRegistration;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.ValidationException;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * JAX-RS Example
 * <p/>
 * This class produces a RESTful service to read/write the contents of the members table.
 */
@Path("/members")
@RequestScoped
public class MemberResourceRESTService {

    @Inject
    private Logger log;

    @Inject
    private Validator validator;

    @Inject
    private MemberRepository repository;

    @Inject
    MemberRegistration registration;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Member> listAllMembers() {
        return repository.findAllOrderedByName();
    }

    @GET
    @Path("/{id:[0-9][0-9]*}")
    @Produces(MediaType.APPLICATION_JSON)
    public Member lookupMemberById(@PathParam("id") long id) {
        Member member = repository.findById(id);
        handleNonexistingMember(id, member);
        return member;
    }

    private void handleNonexistingMember(long id, Member member) {
        if (member == null) {
            throw new WebApplicationException(
                Response
                    .status(Response.Status.NOT_FOUND)
                    .entity(ImmutableMap.of("error", "there is no member whose id is " + id))
                    .build());
        }
    }

    /**
     * Creates a new member from the values provided. Performs validation, and will return a JAX-RS response with either 200 ok,
     * or with a map of fields, and related errors.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createMember(Member member) {

        Response.ResponseBuilder builder = validateMemberAndHandleExceptions(member);

        if (builder != null)
            return builder.build();
        try {
            registration.register(member);
            // Create an "ok" response
            builder = Response.ok();
        } catch (Exception e) {
            builder = handleServerException(e);
        }

        return builder.build();
    }

    @PATCH
    @Consumes("application/json-patch")
    @Path("/{id:[0-9][0-9]*}")
    public Response patchMember(@PathParam("id") long id, JsonPatchRequest patch) {
        log.info("received patch for member " + id + ": \n" + patch);
        Member originalMember = lookupMemberById(id);
        Member updatedMember = patch.apply(originalMember);
        return updateMember(id, updatedMember);
    }


    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{id:[0-9][0-9]*}")
    public Response updateMember(@PathParam("id") long id, Member updatedMember) {
        checkExists(id);
        checkIdPresent(updatedMember);
        checkIdNotChanged(id, updatedMember);

        Response.ResponseBuilder builder = validateMemberAndHandleExceptions(updatedMember);

        if (builder != null)
            return builder.build();
        try {
            registration.update(updatedMember);
            // Create an "ok" response
            builder = Response.status(Response.Status.NO_CONTENT);
        } catch (Exception e) {
            builder = handleServerException(e);
        }

        return builder.build();
    }

    private void checkExists(long id) {
        if (repository.findById(id) == null)
            throw new WebApplicationException(
                Response
                    .status(Response.Status.NOT_FOUND)
                    .entity(ImmutableMap.of(
                        "error", "there is no member whose id is " + id,
                        "note", "this server only supports PUT for resource updates, not resource creation"))
                    .build()
            );
    }

    private void checkIdPresent(Member updatedMember) {
        if (updatedMember.getId() == null)
            throw new WebApplicationException(
                Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(ImmutableMap.of("error", "id cannot be removed"))
                    .build()
            );
    }

    private void checkIdNotChanged(long id, Member updatedMember) {
        if (id != updatedMember.getId())
            throw new WebApplicationException(
                Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(ImmutableMap.of("error", "id cannot be changed"))
                    .build()
            );
    }

    private Response.ResponseBuilder validateMemberAndHandleExceptions(Member member) {
        Response.ResponseBuilder builder = null;

        try {
            // Validates member using bean validation
            validateMember(member);
            builder = null;
        } catch (ConstraintViolationException ce) {
            // Handle bean validation issues
            builder = createViolationResponse(ce.getConstraintViolations());
        } catch (ValidationException e) {
            // Handle the unique constrain violation
            Map<String, String> responseObj = new HashMap<String, String>();
            responseObj.put("email", "Email taken");
            builder = Response
                .status(Response.Status.CONFLICT)
                .entity(responseObj)
                .type(MediaType.APPLICATION_JSON_TYPE);
        }
        return builder;
    }

    private Response.ResponseBuilder handleServerException(Exception e) {
        Response.ResponseBuilder builder;// Handle generic exceptions
        Map<String, String> responseObj = new HashMap<String, String>();
        responseObj.put("error", e.getMessage());
        builder = Response.status(Response.Status.BAD_REQUEST).entity(responseObj);
        return builder;
    }

    /**
     * <p>
     * Validates the given Member variable and throws validation exceptions based on the type of error. If the error is standard
     * bean validation errors then it will throw a ConstraintValidationException with the set of the constraints violated.
     * </p>
     * <p>
     * If the error is caused because an existing member with the same email is registered it throws a regular validation
     * exception so that it can be interpreted separately.
     * </p>
     *
     * @param member Member to be validated
     * @throws ConstraintViolationException If Bean Validation errors exist
     * @throws ValidationException          If member with the same email already exists
     */
    private void validateMember(Member member) throws ConstraintViolationException, ValidationException {
        // Create a bean validator and check for issues.
        Set<ConstraintViolation<Member>> violations = validator.validate(member);

        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(new HashSet<ConstraintViolation<?>>(violations));
        }

        // Check the uniqueness of the email address
        if (emailAlreadyExists(member.getId(), member.getEmail())) {
            throw new ValidationException("Unique Email Violation");
        }
    }

    /**
     * Creates a JAX-RS "Bad Request" response including a map of all violation fields, and their message. This can then be used
     * by clients to show violations.
     *
     * @param violations A set of violations that needs to be reported
     * @return JAX-RS response containing all violations
     */
    private Response.ResponseBuilder createViolationResponse(Set<ConstraintViolation<?>> violations) {
        log.fine("Validation completed. violations found: " + violations.size());

        Map<String, String> responseObj = new HashMap<String, String>();

        for (ConstraintViolation<?> violation : violations) {
            responseObj.put(violation.getPropertyPath().toString(), violation.getMessage());
        }

        return Response
            .status(Response.Status.BAD_REQUEST)
            .entity(responseObj)
            .type(MediaType.APPLICATION_JSON_TYPE);
    }

    /**
     * Checks if a member with the same email address is already registered. This is the only way to easily capture the
     * "@UniqueConstraint(columnNames = "email")" constraint from the Member class.
     *
     * @param newMemberId
     * @param email       The email to check
     * @return True if the email already exists, and false otherwise
     */
    public boolean emailAlreadyExists(Long newMemberId, String email) {
        Member member = null;
        try {
            member = repository.findByEmail(email);
        } catch (NoResultException e) {
            // ignore
        }
        if (member != null) {
            return newMemberId == null || !newMemberId.equals(member.getId());
        }
        return false;
    }
}
