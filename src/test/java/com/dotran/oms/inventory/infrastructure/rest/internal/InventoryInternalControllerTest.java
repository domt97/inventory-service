package com.dotran.oms.inventory.infrastructure.rest.internal;

import com.dotran.oms.inventory.application.command.CreateInventoryCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.oms.inventory.infrastructure.rest.dto.request.CreateInventoryRequest;
import com.dotran.oms.inventory.infrastructure.rest.dto.response.InventoryDetailResponse;
import com.dotran.oms.inventory.infrastructure.rest.mapper.InventoryRestMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryInternalController")
class InventoryInternalControllerTest {

    @Mock
    private CreateInventoryUseCase createInventoryUseCase;

    @Mock
    private InventoryRestMapper restMapper;

    @InjectMocks
    private InventoryInternalController controller;

    @Test
    @DisplayName("create — maps request to command, calls use case, maps each DTO to response")
    void shouldCreateInventory() {
        CreateInventoryRequest request = mock(CreateInventoryRequest.class);
        CreateInventoryCmd cmd = mock(CreateInventoryCmd.class);
        InventoryDetailDto dto = mock(InventoryDetailDto.class);
        InventoryDetailResponse response = mock(InventoryDetailResponse.class);

        when(restMapper.toCreateInventoryCmd(request)).thenReturn(cmd);
        when(createInventoryUseCase.create(cmd)).thenReturn(List.of(dto));
        when(restMapper.toInventoryDetailResponse(dto)).thenReturn(response);

        List<InventoryDetailResponse> result = controller.create(request);

        assertEquals(1, result.size());
        assertSame(response, result.get(0));
        verify(createInventoryUseCase).create(cmd);
    }
}
