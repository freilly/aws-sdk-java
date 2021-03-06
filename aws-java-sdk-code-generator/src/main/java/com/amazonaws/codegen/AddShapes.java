/*
 * Copyright (c) 2016. Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.codegen;

import com.amazonaws.codegen.internal.TypeUtils;
import com.amazonaws.codegen.model.config.customization.CustomizationConfig;
import com.amazonaws.codegen.model.intermediate.EnumModel;
import com.amazonaws.codegen.model.intermediate.ListModel;
import com.amazonaws.codegen.model.intermediate.MapModel;
import com.amazonaws.codegen.model.intermediate.MemberModel;
import com.amazonaws.codegen.model.intermediate.ParameterHttpMapping;
import com.amazonaws.codegen.model.intermediate.Protocol;
import com.amazonaws.codegen.model.intermediate.ReturnTypeModel;
import com.amazonaws.codegen.model.intermediate.ShapeModel;
import com.amazonaws.codegen.model.intermediate.VariableModel;
import com.amazonaws.codegen.model.service.Location;
import com.amazonaws.codegen.model.service.Member;
import com.amazonaws.codegen.model.service.ServiceModel;
import com.amazonaws.codegen.model.service.Shape;
import com.amazonaws.codegen.naming.NamingStrategy;
import com.amazonaws.util.StringUtils;

import java.util.List;
import java.util.Map;

import static com.amazonaws.codegen.internal.DocumentationUtils.generateGetterDocumentation;
import static com.amazonaws.codegen.internal.DocumentationUtils.generateSetterDocumentation;
import static com.amazonaws.codegen.internal.TypeUtils.LIST_AUTO_CONSTRUCT_IMPL;
import static com.amazonaws.codegen.internal.TypeUtils.LIST_DEFAULT_IMPL;
import static com.amazonaws.codegen.internal.TypeUtils.LIST_INTERFACE;
import static com.amazonaws.codegen.internal.TypeUtils.MAP_AUTO_CONSTRUCT_IMPL;
import static com.amazonaws.codegen.internal.TypeUtils.MAP_DEFAULT_IMPL;
import static com.amazonaws.codegen.internal.TypeUtils.MAP_INTERFACE;
import static com.amazonaws.codegen.internal.TypeUtils.getDataTypeMapping;
import static com.amazonaws.codegen.internal.Utils.capitialize;
import static com.amazonaws.codegen.internal.Utils.isEnumShape;
import static com.amazonaws.codegen.internal.Utils.isListShape;
import static com.amazonaws.codegen.internal.Utils.isMapShape;
import static com.amazonaws.codegen.internal.Utils.isScalar;

abstract class AddShapes {

    private final IntermediateModelBuilder builder;
    private final NamingStrategy namingStrategy;

    AddShapes(IntermediateModelBuilder builder) {
        this.builder = builder;
        this.namingStrategy = builder.getNamingStrategy();
    }

    protected final TypeUtils getTypeUtils() {
        return builder.getTypeUtils();
    }

    protected final NamingStrategy getNamingStrategy() {
        return namingStrategy;
    }

    protected final ServiceModel getServiceModel() {
        return builder.getService();
    }

    protected final CustomizationConfig getCustomizationConfig() {
        return builder.getCustomConfig();
    }

    protected final ShapeModel generateShapeModel(String javaClassName, String shapeName) {
        final ShapeModel shapeModel = new ShapeModel(shapeName);
        shapeModel.setShapeName(javaClassName);
        final Shape shape = getServiceModel().getShapes().get(shapeName);

        shapeModel.setDocumentation(shape.getDocumentation());
        shapeModel.setVariable(new VariableModel(getNamingStrategy().getVariableName(javaClassName),
                                                 javaClassName));
        // contains the list of c2j member names that are required for this shape.
        shapeModel.setRequired(shape.getRequired());
        shapeModel.setDeprecated(shape.isDeprecated());
        shapeModel.setWrapper(shape.isWrapper());

        final Map<String, Member> members = shape.getMembers();

        if (members != null) {
            boolean hasHeaderMember = false;
            boolean hasStatusCodeMember = false;
            boolean hasPayloadMember = false;
            boolean hasStreamingMember = false;

            for (Map.Entry<String, Member> memberEntry : members.entrySet()) {

                String c2jMemberName = memberEntry.getKey();
                Member c2jMemberDefinition = memberEntry.getValue();
                Shape parentShape = shape;

                MemberModel memberModel = generateMemberModel(c2jMemberName, c2jMemberDefinition,
                                                              getProtocol(), parentShape,
                                                              getServiceModel().getShapes());

                if (memberModel.getHttp().getLocation() == Location.HEADER) {
                    hasHeaderMember = true;

                } else if (memberModel.getHttp().getLocation() == Location.STATUS_CODE) {
                    hasStatusCodeMember = true;

                } else if (memberModel.getHttp().getIsPayload()) {
                    hasPayloadMember = true;
                    if (memberModel.getHttp().getIsStreaming()) {
                        hasStreamingMember = true;
                    }
                }

                shapeModel.addMember(memberModel);
            }

            shapeModel.withHasHeaderMember(hasHeaderMember)
                    .withHasStatusCodeMember(hasStatusCodeMember)
                    .withHasPayloadMember(hasPayloadMember)
                    .withHasStreamingMember(hasStreamingMember);
        }

        final List<String> enumValues = shape.getEnumValues();
        if (enumValues != null && !enumValues.isEmpty()) {
            for (String enumValue : enumValues) {
                // TODO handle useRealName from Coral if explicitly mentioned in
                // the customization.
                shapeModel.addEnum(
                        new EnumModel(getNamingStrategy().getEnumValueName(enumValue), enumValue));
            }
        }

        return shapeModel;
    }

    private MemberModel generateMemberModel(String c2jMemberName, Member c2jMemberDefinition,
                                            String protocol, Shape parentShape,
                                            Map<String, Shape> allC2jShapes) {
        final String c2jShapeName = c2jMemberDefinition.getShape();
        final String variableName = getNamingStrategy().getVariableName(c2jMemberName);
        final String variableType = getTypeUtils().getJavaDataType(allC2jShapes, c2jShapeName);
        final String variableDeclarationType = getTypeUtils()
                .getJavaDataType(allC2jShapes, c2jShapeName, getCustomizationConfig());

        //If member is idempotent, then it should be of string type
        //Else throw IllegalArgumentException.
        if (c2jMemberDefinition.isIdempotencyToken() &&
            !variableType.equals(String.class.getSimpleName())) {
            throw new IllegalArgumentException(c2jMemberName +
                                               " is idempotent. It's shape should be string type but it is of " +
                                               variableType + " type.");
        }


        final MemberModel memberModel = new MemberModel();

        memberModel.withC2jName(c2jMemberName)
                   .withC2jShape(c2jShapeName)
                   .withName(capitialize(c2jMemberName))
                   .withVariable(new VariableModel(variableName, variableType, variableDeclarationType)
                                         .withDocumentation(c2jMemberDefinition.getDocumentation()))
                   .withSetterModel(new VariableModel(variableName, variableType, variableDeclarationType)
                                            .withDocumentation(generateSetterDocumentation()))
                   .withGetterModel(new ReturnTypeModel(variableType).withDocumentation(generateGetterDocumentation()));

        memberModel.setDocumentation(c2jMemberDefinition.getDocumentation());
        memberModel.setDeprecated(c2jMemberDefinition.isDeprecated());
        memberModel.withGetterMethodName(namingStrategy.getGetterMethodName(c2jMemberName))
                   .withSetterMethodName(namingStrategy.getSetterMethodName(c2jMemberName))
                   .withFluentSetterMethodName(namingStrategy.getFluentSetterMethodName(c2jMemberName));

        memberModel.setIdempotencyToken(c2jMemberDefinition.isIdempotencyToken());

        // Pass the xmlNameSpace from the member reference
        if (c2jMemberDefinition.getXmlNamespace() != null) {
            memberModel.setXmlNameSpaceUri(c2jMemberDefinition.getXmlNamespace().getUri());
        }

        // Additional member model metadata for list/map/enum types
        fillContainerTypeMemberMetadata(allC2jShapes, c2jMemberDefinition.getShape(), memberModel,
                                        protocol);

        final ParameterHttpMapping httpMapping = generateParameterHttpMapping(parentShape,
                                                                              c2jMemberName,
                                                                              c2jMemberDefinition,
                                                                              protocol,
                                                                              allC2jShapes);

        final String payload = parentShape.getPayload();
        httpMapping.withPayload(payload != null && payload.equals(c2jMemberName))
                   .withStreaming(allC2jShapes.get(c2jMemberDefinition.getShape()).isStreaming());

        memberModel.setHttp(httpMapping);
        memberModel.setJsonValue(c2jMemberDefinition.isJsonvalue());

        return memberModel;
    }

    private ParameterHttpMapping generateParameterHttpMapping(Shape parentShape,
                                                              String memberName,
                                                              Member member,
                                                              String protocol,
                                                              Map<String, Shape> allC2jShapes) {

        ParameterHttpMapping mapping = new ParameterHttpMapping();

        Shape memberShape = allC2jShapes.get(member.getShape());

        mapping.withLocation(Location.forValue(member.getLocation()))
               .withPayload(member.isPayload())
               .withStreaming(member.isStreaming())
               .withFlattened(member.isFlattened() || memberShape.isFlattened())
               .withUnmarshallLocationName(deriveUnmarshallerLocationName(memberName, member))
               .withMarshallLocationName(deriveMarshallerLocationName(memberName, member, protocol))
               .withIsGreedy(isGreedy(parentShape, allC2jShapes, mapping));

        return mapping;
    }

    /**
     * @param parentShape  Shape containing the member in question.
     * @param allC2jShapes All shapes in the service model.
     * @param mapping      Mapping being built.
     * @return True if the member is bound to a greedy label, false otherwise.
     */
    private boolean isGreedy(Shape parentShape, Map<String, Shape> allC2jShapes, ParameterHttpMapping mapping) {
        if (mapping.getLocation() == Location.URI) {
            // If the location is URI we can assume the parent shape is an input shape.
            final String requestUri = findRequestUri(parentShape, allC2jShapes);
            if (requestUri.contains(String.format("{%s+}", mapping.getMarshallLocationName()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Given an input shape, finds the Request URI for the operation that input is referenced from.
     *
     * @param parentShape  Input shape to find operation's request URI for.
     * @param allC2jShapes All shapes in the service model.
     * @return Request URI for operation.
     * @throws RuntimeException If operation can't be found.
     */
    private String findRequestUri(Shape parentShape, Map<String, Shape> allC2jShapes) {
        return builder.getService().getOperations().values().stream()
                .filter(o -> o.getInput() != null)
                .filter(o -> allC2jShapes.get(o.getInput().getShape()).equals(parentShape))
                .map(o -> o.getHttp().getRequestUri())
                .findFirst().orElseThrow(() -> new RuntimeException("Could not find request URI for input shape"));
    }

    private String deriveUnmarshallerLocationName(String memberName, Member member) {

        final String locationName = member.getLocationName();

        if (locationName != null && !locationName.trim().isEmpty()) {
            return locationName;
        }

        return memberName;
    }

    private String deriveMarshallerLocationName(String memberName, Member member, String protocol) {
        final String queryName = member.getQueryName();
        if (queryName != null && !queryName.trim().isEmpty()) {
            return queryName;
        } else {
            final String locationName = member.getLocationName();
            if (locationName != null && !locationName.trim().isEmpty()) {
                if (protocol.equals(Protocol.EC2.getValue())) {
                    return StringUtils.upperCase(locationName.substring(0, 1)) +
                           locationName.substring(1);
                }
                return locationName;
            } else {
                return memberName;
            }
        }
    }

    private void fillContainerTypeMemberMetadata(Map<String, Shape> c2jShapes,
                                                 String memberC2jShapeName, MemberModel memberModel,
                                                 String protocol) {

        final Shape memberC2jShape = c2jShapes.get(memberC2jShapeName);

        if (isListShape(memberC2jShape)) {

            MemberModel listMemberModel;

            Member listMemberDefinition = memberC2jShape.getListMember();
            String listMemberC2jShapeName = listMemberDefinition.getShape();
            Shape listMemberC2jShape = c2jShapes.get(listMemberC2jShapeName);

            listMemberModel = generateMemberModel("member", listMemberDefinition, protocol,
                                                  memberC2jShape, c2jShapes);
            final String listImpl = getCustomizationConfig().isUseAutoConstructList() ?
                    getDataTypeMapping(LIST_AUTO_CONSTRUCT_IMPL) :
                    getDataTypeMapping(LIST_DEFAULT_IMPL);
            memberModel.setListModel(
                    new ListModel(getTypeUtils().getJavaDataType(c2jShapes, listMemberC2jShapeName),
                                  memberC2jShape.getListMember().getLocationName(), listImpl,
                                  getDataTypeMapping(LIST_INTERFACE), listMemberModel));

            if (listMemberC2jShape.getEnumValues() != null) {
                memberModel
                        .setEnumType(getNamingStrategy().getJavaClassName(listMemberC2jShapeName));
            }
        } else if (isMapShape(memberC2jShape)) {

            MemberModel mapKeyModel = null;
            MemberModel mapValueModel;

            Member mapKeyMemberDefinition = memberC2jShape.getMapKeyType();
            String mapKeyShapeName = mapKeyMemberDefinition.getShape();
            Shape mapKeyShape = c2jShapes.get(mapKeyShapeName);

            Member mapValueMemberDefinition = memberC2jShape.getMapValueType();

            // Only construct the nested key model if the key of the map
            // itself is Enum shape. Throw exception if the nested key type is complex
            // because we don't support complex map keys.
            if (isEnumShape(mapKeyShape)) {
                mapKeyModel = generateMemberModel("key", mapKeyMemberDefinition, protocol,
                                                  memberC2jShape, c2jShapes);
            } else if (!isScalar(mapKeyShape)) {
                throw new IllegalStateException(
                        "The key type of " + mapKeyShapeName + " must be a scalar!");
            }
            mapValueModel = generateMemberModel("value", mapValueMemberDefinition, protocol,
                                                memberC2jShape, c2jShapes);
            final String mapImpl = getCustomizationConfig().isUseAutoConstructMap() ?
                    getDataTypeMapping(MAP_AUTO_CONSTRUCT_IMPL) :
                    getDataTypeMapping(MAP_DEFAULT_IMPL);

            String keyLocation = memberC2jShape.getMapKeyType().getLocationName() != null ?
                    memberC2jShape.getMapKeyType().getLocationName() : "key";

            String valueLocation = memberC2jShape.getMapValueType().getLocationName() != null ?
                    memberC2jShape.getMapValueType().getLocationName() : "value";

            memberModel.setMapModel(new MapModel(mapImpl, getDataTypeMapping(MAP_INTERFACE),
                                                 getTypeUtils().getJavaDataType(c2jShapes,
                                                                                memberC2jShape
                                                                                        .getMapKeyType()
                                                                                        .getShape()),
                                                 keyLocation, mapKeyModel, getTypeUtils()
                                                         .getJavaDataType(c2jShapes, memberC2jShape
                                                                 .getMapValueType().getShape()),
                                                 valueLocation, mapValueModel));

        } else if (memberC2jShape.getEnumValues() != null) { // enum values
            memberModel.withEnumType(getNamingStrategy().getJavaClassName(memberC2jShapeName));
        }
    }

    protected String getProtocol() {
        return getServiceModel().getMetadata().getProtocol();
    }
}
