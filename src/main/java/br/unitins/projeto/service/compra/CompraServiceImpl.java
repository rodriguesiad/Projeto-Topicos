package br.unitins.projeto.service.compra;

import br.unitins.projeto.dto.boleto.BoletoDTO;
import br.unitins.projeto.dto.boleto.BoletoResponseDTO;
import br.unitins.projeto.dto.compra.CompraDTO;
import br.unitins.projeto.dto.compra.CompraResponseDTO;
import br.unitins.projeto.dto.compra.StatusCompraDTO;
import br.unitins.projeto.dto.historico_entrega.HistoricoEntregaDTO;
import br.unitins.projeto.dto.historico_entrega.HistoricoEntregaResponseDTO;
import br.unitins.projeto.dto.pix.PixDTO;
import br.unitins.projeto.dto.pix.PixResponseDTO;
import br.unitins.projeto.model.Boleto;
import br.unitins.projeto.model.Compra;
import br.unitins.projeto.model.HistoricoEntrega;
import br.unitins.projeto.model.ItemCompra;
import br.unitins.projeto.model.Pix;
import br.unitins.projeto.model.StatusCompra;
import br.unitins.projeto.model.TipoChavePix;
import br.unitins.projeto.model.Usuario;
import br.unitins.projeto.repository.BoletoRepository;
import br.unitins.projeto.repository.CompraRepository;
import br.unitins.projeto.repository.HistoricoEntregaRepository;
import br.unitins.projeto.repository.PixRepository;
import br.unitins.projeto.repository.UsuarioRepository;
import br.unitins.projeto.service.endereco_compra.EnderecoCompraService;
import br.unitins.projeto.service.item_compra.ItemCompraService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ApplicationScoped
public class CompraServiceImpl implements CompraService {

    @Inject
    CompraRepository repository;

    @Inject
    ItemCompraService itemCompraService;

    @Inject
    UsuarioRepository usuarioRepository;

    @Inject
    EnderecoCompraService enderecoCompraService;

    @Inject
    HistoricoEntregaRepository historicoEntregaRepository;

    @Inject
    BoletoRepository boletoRepository;

    @Inject
    PixRepository pixRepository;

    @Inject
    Validator validator;

    @Override
    public List<CompraResponseDTO> getAll() {
        List<Compra> list = repository.listAll();
        return list.stream().map(compra -> CompraResponseDTO.valueOf(compra)).collect(Collectors.toList());
    }

    @Override
    public CompraResponseDTO findById(Long id) {
        Compra compra = repository.findById(id);

        if (compra == null)
            throw new NotFoundException("Compra não encontrada.");

        return CompraResponseDTO.valueOf(compra);
    }

    @Override
    @Transactional
    public CompraResponseDTO create(CompraDTO dto, Long idUsuario) {
        validar(dto);

        Compra entity = new Compra();

        entity.setData(LocalDateTime.now());
        entity.setUsuario(this.getUsuario(idUsuario));
        entity.setStatusCompra(StatusCompra.PROCESSANDO);
        entity.setEnderecoCompra(enderecoCompraService.toModel(dto.enderecoCompra()));

        repository.persist(entity);

        List<ItemCompra> itens = new ArrayList<>();
        AtomicReference<Double> preco = new AtomicReference<>(0.0);

        dto.itensCompra().forEach(item -> {
                    ItemCompra itemModel = itemCompraService.toModel(item);
                    itemModel.setCompra(entity);
                    itens.add(itemModel);

                    preco.updateAndGet(v -> v + (itemModel.getPreco() * itemModel.getQuantidade()));
                }
        );

        entity.setItensCompra(itens);
        entity.setTotalCompra(preco.get());

        return CompraResponseDTO.valueOf(entity);
    }

    @Override
    @Transactional
    public CompraResponseDTO alterStatusCompra(Long idCompra, StatusCompraDTO dto) {
        Compra compra = this.getCompra(idCompra);

        try {
            if (!StatusCompra.valueOf(dto.statusCompra()).equals(StatusCompra.CANCELADA)) {
                compra.setStatusCompra(StatusCompra.valueOf(dto.statusCompra()));
            }
        } catch (Exception e) {
            throw new NotFoundException("Status não encontrado.");
        }

        return CompraResponseDTO.valueOf(compra);
    }

    @Override
    public List<CompraResponseDTO> findByUsuario(Long idUsuario) {
        List<Compra> list = repository.findByUsuario(idUsuario);
        return list.stream().map(compra -> CompraResponseDTO.valueOf(compra)).collect(Collectors.toList());
    }

    @Override
    public List<HistoricoEntregaResponseDTO> getHistoricoEntrega(Long idCompra) {
        List<HistoricoEntrega> list = historicoEntregaRepository.findByCompra(idCompra);
        return list.stream().map(HistoricoEntregaResponseDTO::new).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public HistoricoEntregaResponseDTO insertHistoricoEntrega(Long idCompra, @Valid HistoricoEntregaDTO dto) {
        Compra compra = this.getCompra(idCompra);

        HistoricoEntrega historicoEntrega = new HistoricoEntrega();
        historicoEntrega.setTitulo(dto.titulo());
        historicoEntrega.setDescricao(dto.descricao());
        historicoEntrega.setData(LocalDateTime.now());
        historicoEntrega.setCompra(compra);

        historicoEntregaRepository.persist(historicoEntrega);

        if (compra.getHistoricoEntrega().isEmpty())
            compra.setHistoricoEntrega(new ArrayList<>());

        compra.getHistoricoEntrega().add(historicoEntrega);

        return new HistoricoEntregaResponseDTO(historicoEntrega);
    }

    @Override
    @Transactional
    public BoletoResponseDTO pagarPorBoleto(Long idCompra, @Valid BoletoDTO dto) throws Exception {
        Compra compra = getCompra(idCompra);

        if (compra.getMetodoDePagamento() != null) {
            throw new Exception("A compra já possuiu método de pagamento.");
        }

        Boleto boleto = new Boleto();
        boleto.setNumeroBoleto(dto.numeroBoleto());
        boleto.setVencimento(dto.vencimento());
        boleto.setCompra(compra);

        boletoRepository.persist(boleto);
        compra.setMetodoDePagamento(boleto);

        return new BoletoResponseDTO(boleto);
    }

    @Override
    @Transactional
    public PixResponseDTO pagarPorPix(Long idCompra, @Valid PixDTO dto) throws Exception {
        Compra compra = getCompra(idCompra);

        if (compra.getMetodoDePagamento() != null) {
            throw new Exception("A compra já possui método de pagamento.");
        }

        TipoChavePix tipoChavePix = TipoChavePix.valueOf(dto.tipoChavePix());
        Pix pix = new Pix();
        pix.setChave(dto.chave());
        pix.setTipoChavePix(tipoChavePix);
        pix.setCompra(compra);

        pixRepository.persist(pix);
        compra.setMetodoDePagamento(pix);

        return new PixResponseDTO(pix);
    }

    @Override
    public Response getMetodoPagamento(Long idCompra) {

        Compra compra = getCompra(idCompra);

        if (compra.getMetodoDePagamento() == null) {
            throw new NotFoundException("Essa compra não possui método de pagamento");
        }

        Boleto boleto = boletoRepository.findByIdAndCompra(compra.getId(), compra.getMetodoDePagamento().getId());
        Pix pix = pixRepository.findByIdAndCompra(compra.getId(), compra.getMetodoDePagamento().getId());

        if (boleto != null) {
            BoletoResponseDTO boletoResponseDTO = new BoletoResponseDTO(boleto);
            return Response.ok(boletoResponseDTO).build();
        }

        if (pix != null) {
            PixResponseDTO pixResponseDTO = new PixResponseDTO(pix);
            return Response.ok(pixResponseDTO).build();
        }

        return null;
    }

    private void validar(CompraDTO dto) throws ConstraintViolationException {
        Set<ConstraintViolation<CompraDTO>> violations = validator.validate(dto);

        if (!violations.isEmpty())
            throw new ConstraintViolationException(violations);
    }

    private Usuario getUsuario(Long id) {
        Usuario usuario = usuarioRepository.findById(id);

        if (usuario == null)
            throw new NotFoundException("Usuário não encontrado.");

        return usuario;
    }

    private Compra getCompra(Long id) {
        Compra compra = repository.findById(id);

        if (compra == null)
            throw new NotFoundException("Compra não encontrada.");

        return compra;
    }

}
